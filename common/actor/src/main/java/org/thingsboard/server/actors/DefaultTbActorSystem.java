/**
 * Copyright © 2016-2023 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.actors;

import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.msg.TbActorMsg;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@Data
public class DefaultTbActorSystem implements TbActorSystem {

    private final ConcurrentMap<String, Dispatcher> dispatchers = new ConcurrentHashMap<>();
    private final ConcurrentMap<TbActorId, TbActorMailbox> actors = new ConcurrentHashMap<>();
    private final ConcurrentMap<TbActorId, ReentrantLock> actorCreationLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<TbActorId, Set<TbActorId>> parentChildMap = new ConcurrentHashMap<>();

    @Getter
    private final TbActorSystemSettings settings;
    @Getter
    private final ScheduledExecutorService scheduler;

    public DefaultTbActorSystem(TbActorSystemSettings settings) {
        this.settings = settings;
        this.scheduler = Executors.newScheduledThreadPool(settings.getSchedulerPoolSize(), ThingsBoardThreadFactory.forName("actor-system-scheduler"));
    }

    @Override
    public void createDispatcher(String dispatcherId, ExecutorService executor) {
        Dispatcher current = dispatchers.putIfAbsent(dispatcherId, new Dispatcher(dispatcherId, executor));
        if (current != null) {
            throw new RuntimeException("Dispatcher with id [" + dispatcherId + "] is already registered!");
        }
    }

    @Override
    public void destroyDispatcher(String dispatcherId) {
        Dispatcher dispatcher = dispatchers.remove(dispatcherId);
        if (dispatcher != null) {
            dispatcher.getExecutor().shutdownNow();
        } else {
            throw new RuntimeException("Dispatcher with id [" + dispatcherId + "] is not registered!");
        }
    }

    @Override
    public TbActorRef getActor(TbActorId actorId) {
        return actors.get(actorId);
    }

    @Override
    public TbActorRef createRootActor(String dispatcherId, TbActorCreator creator) {
        return createActor(dispatcherId, creator, null);
    }

    @Override
    public TbActorRef createChildActor(String dispatcherId, TbActorCreator creator, TbActorId parent) {
        return createActor(dispatcherId, creator, parent);
    }

    /**
     * 创建 Actor，关键方法
     */
    private TbActorRef createActor(String dispatcherId, TbActorCreator creator, TbActorId parent) {
        //获取调度器
        Dispatcher dispatcher = dispatchers.get(dispatcherId);
        if (dispatcher == null) {
            log.warn("Dispatcher with id [{}] is not registered!", dispatcherId);
            throw new RuntimeException("Dispatcher with id [" + dispatcherId + "] is not registered!");
        }

        //获取标识
        TbActorId actorId = creator.createActorId();
        //获取邮箱
        TbActorMailbox actorMailbox = actors.get(actorId);
        if (actorMailbox != null) {
            log.debug("Actor with id [{}] is already registered!", actorId);
        } else {
            //创建邮箱
            //获取 Actor 创建锁
            Lock actorCreationLock = actorCreationLocks.computeIfAbsent(actorId, id -> new ReentrantLock());
            //加锁
            actorCreationLock.lock();
            try {
                //获取邮箱
                actorMailbox = actors.get(actorId);
                //二次验证
                if (actorMailbox == null) {
                    log.debug("Creating actor with id [{}]!", actorId);
                    //创建 Actor
                    TbActor actor = creator.createActor();
                    TbActorRef parentRef = null;
                    if (parent != null) {
                        //获取父 Actor
                        parentRef = getActor(parent);
                        if (parentRef == null) {
                            throw new TbActorNotRegisteredException(parent, "Parent Actor with id [" + parent + "] is not registered!");
                        }
                    }
                    //创建邮箱
                    TbActorMailbox mailbox = new TbActorMailbox(this, settings, actorId, parentRef, actor, dispatcher);
                    //将邮箱放入集合
                    actors.put(actorId, mailbox);
                    //初始化 Actor
                    mailbox.initActor();
                    actorMailbox = mailbox;
                    if (parent != null) {
                        //将标识加入对应的父子集合
                        parentChildMap.computeIfAbsent(parent, id -> ConcurrentHashMap.newKeySet()).add(actorId);
                    }
                } else {
                    log.debug("Actor with id [{}] is already registered!", actorId);
                }
            } finally {
                //解锁
                actorCreationLock.unlock();
                //移除锁
                actorCreationLocks.remove(actorId);
            }
        }
        //返回邮箱
        return actorMailbox;
    }

    @Override
    public void tellWithHighPriority(TbActorId target, TbActorMsg actorMsg) {
        tell(target, actorMsg, true);
    }

    @Override
    public void tell(TbActorId target, TbActorMsg actorMsg) {
        tell(target, actorMsg, false);
    }

    /**
     * 告知 Actor 消息，关键方法
     */
    private void tell(TbActorId target, TbActorMsg actorMsg, boolean highPriority) {
        //获取邮箱
        TbActorMailbox mailbox = actors.get(target);
        if (mailbox == null) {
            throw new TbActorNotRegisteredException(target, "Actor with id [" + target + "] is not registered!");
        }
        if (highPriority) {
            //使用高优先级告知消息
            mailbox.tellWithHighPriority(actorMsg);
        } else {
            //告知消息
            mailbox.tell(actorMsg);
        }
    }


    @Override
    public void broadcastToChildren(TbActorId parent, TbActorMsg msg) {
        broadcastToChildren(parent, id -> true, msg);
    }

    @Override
    public void broadcastToChildren(TbActorId parent, Predicate<TbActorId> childFilter, TbActorMsg msg) {
        Set<TbActorId> children = parentChildMap.get(parent);
        if (children != null) {
            children.stream().filter(childFilter).forEach(id -> tell(id, msg));
        }
    }

    @Override
    public List<TbActorId> filterChildren(TbActorId parent, Predicate<TbActorId> childFilter) {
        Set<TbActorId> children = parentChildMap.get(parent);
        if (children != null) {
            return children.stream().filter(childFilter).collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public void stop(TbActorRef actorRef) {
        stop(actorRef.getActorId());
    }

    @Override
    public void stop(TbActorId actorId) {
        Set<TbActorId> children = parentChildMap.remove(actorId);
        if (children != null) {
            for (TbActorId child : children) {
                stop(child);
            }
        }
        TbActorMailbox mailbox = actors.remove(actorId);
        if (mailbox != null) {
            mailbox.destroy(null);
        }
    }

    @Override
    public void stop() {
        dispatchers.values().forEach(dispatcher -> {
            dispatcher.getExecutor().shutdown();
            try {
                dispatcher.getExecutor().awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("[{}] Failed to stop dispatcher", dispatcher.getDispatcherId(), e);
            }
        });
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        actors.clear();
    }

}
