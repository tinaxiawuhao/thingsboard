/**
 * Copyright © 2016-2023 The Thingsboard Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.actors;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.msg.MsgType;
import org.thingsboard.server.common.msg.TbActorError;
import org.thingsboard.server.common.msg.TbActorMsg;
import org.thingsboard.server.common.msg.TbActorStopReason;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Slf4j
@Getter
@RequiredArgsConstructor
public final class TbActorMailbox implements TbActorCtx {
    private static final boolean HIGH_PRIORITY = true;
    private static final boolean NORMAL_PRIORITY = false;

    private static final boolean FREE = false;
    private static final boolean BUSY = true;

    private static final boolean NOT_READY = false;
    private static final boolean READY = true;

    private final TbActorSystem system;
    private final TbActorSystemSettings settings;
    private final TbActorId selfId;
    private final TbActorRef parentRef;
    private final TbActor actor;
    private final Dispatcher dispatcher;
    private final ConcurrentLinkedQueue<TbActorMsg> highPriorityMsgs = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TbActorMsg> normalPriorityMsgs = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean busy = new AtomicBoolean(FREE);
    private final AtomicBoolean ready = new AtomicBoolean(NOT_READY);
    private final AtomicBoolean destroyInProgress = new AtomicBoolean();
    private volatile TbActorStopReason stopReason;

    /**
     * 初始化 Actor
     */
    public void initActor() {
        //异步初始化
        dispatcher.getExecutor().execute(() -> tryInit(1));
    }

    /**
     * 尝试初始化 Actor，关键方法
     */
    private void tryInit(int attempt) {
        try {
            log.debug("[{}] Trying to init actor, attempt: {}", selfId, attempt);
            //判断销毁状态
            if (!destroyInProgress.get()) {
                //初始化	Actor
                actor.init(this);
                if (!destroyInProgress.get()) {
                    //设置状态
                    ready.set(READY);
                    //尝试处理队列消息
                    tryProcessQueue(false);
                }
            }
        } catch (Throwable t) {
            InitFailureStrategy strategy;
            //尝试计数加一
            int attemptIdx = attempt + 1;
            //获取初始化失败策略
            if (isUnrecoverable(t)) {
                strategy = InitFailureStrategy.stop();
            } else {
                log.debug("[{}] Failed to init actor, attempt: {}", selfId, attempt, t);
                strategy = actor.onInitFailure(attempt, t);
            }
            if (strategy.isStop() || (settings.getMaxActorInitAttempts() > 0 && attemptIdx > settings.getMaxActorInitAttempts())) {
                //失败策略为停止或尝试次数已超过最大次数
                log.info("[{}] Failed to init actor, attempt {}, going to stop attempts.", selfId, attempt, t);
                //记录停止原因为初始化失败
                stopReason = TbActorStopReason.INIT_FAILED;
                //销毁
                destroy(t.getCause());
            } else if (strategy.getRetryDelay() > 0) {
                log.info("[{}] Failed to init actor, attempt {}, going to retry in attempts in {}ms", selfId, attempt, strategy.getRetryDelay());
                log.debug("[{}] Error", selfId, t);
                //设置给定的延时后重新尝试初始化
                system.getScheduler().schedule(() -> dispatcher.getExecutor().execute(() -> tryInit(attemptIdx)), strategy.getRetryDelay(), TimeUnit.MILLISECONDS);
            } else {
                log.info("[{}] Failed to init actor, attempt {}, going to retry immediately", selfId, attempt);
                log.debug("[{}] Error", selfId, t);
                //立即重新尝试初始化
                dispatcher.getExecutor().execute(() -> tryInit(attemptIdx));
            }
        }
    }

    private static boolean isUnrecoverable(Throwable t) {
        if (t instanceof TbActorException && t.getCause() != null) {
            t = t.getCause();
        }
        return t instanceof TbActorError && ((TbActorError) t).isUnrecoverable();
    }

    /**
     * 消息入队，关键方法
     */
    private void enqueue(TbActorMsg msg, boolean highPriority) {
        //判断销毁状态
        if (!destroyInProgress.get()) {
            if (highPriority) {
                //将消息放入高优先级队列
                highPriorityMsgs.add(msg);
            } else {
                //将消息放入普通优先级队列
                normalPriorityMsgs.add(msg);
            }
            //尝试处理队列消息
            tryProcessQueue(true);
        } else {
            //处于销毁状态
            if (highPriority && msg.getMsgType().equals(MsgType.RULE_NODE_UPDATED_MSG)) {
                //当前为高优先级的规则节点更新消息
                //加锁
                synchronized (this) {
                    if (stopReason == TbActorStopReason.INIT_FAILED) {
                        //当前停止原因为初始化失败
                        //更改销毁状态
                        destroyInProgress.set(false);
                        //重置停止原因
                        stopReason = null;
                        //再次初始化 Actor
                        initActor();
                    } else {
                        //回调 Actor停止原因
                        msg.onTbActorStopped(stopReason);
                    }
                }
            } else {
                //回调 Actor停止原因
                msg.onTbActorStopped(stopReason);
            }
        }
    }

    private void tryProcessQueue(boolean newMsg) {
        if (ready.get() == READY) {
            //已初始化完成
            if (newMsg || !highPriorityMsgs.isEmpty() || !normalPriorityMsgs.isEmpty()) {
                //当前有新的消息或消息队列不为空（有待处理的消息）
                //判断当前状态是否为空闲，并改为繁忙
                if (busy.compareAndSet(FREE, BUSY)) {
                    //异步处理邮箱
                    dispatcher.getExecutor().execute(this::processMailbox);
                } else {
                    //当前状态为繁忙
                    log.trace("[{}] MessageBox is busy, new msg: {}", selfId, newMsg);
                }
            } else {
                log.trace("[{}] MessageBox is empty, new msg: {}", selfId, newMsg);
            }
        } else {
            log.trace("[{}] MessageBox is not ready, new msg: {}", selfId, newMsg);
        }
    }

    private void processMailbox() {
        //标记是否有更多的消息
        boolean noMoreElements = false;
        //根据指定的吞吐量遍历处理
        for (int i = 0; i < settings.getActorThroughput(); i++) {
            //从高优先级队列获取消息
            TbActorMsg msg = highPriorityMsgs.poll();
            if (msg == null) {
                //从普通优先级队列获取消息
                msg = normalPriorityMsgs.poll();
            }
            if (msg != null) {
                try {
                    log.debug("[{}] Going to process message: {}", selfId, msg);
                    //调用 Actor 处理消息
                    actor.process(msg);
                } catch (TbRuleNodeUpdateException updateException) {
                    //规则节点更新异常视为初始化失败
                    stopReason = TbActorStopReason.INIT_FAILED;
                    //销毁
                    destroy(updateException.getCause());
                } catch (Throwable t) {
                    log.debug("[{}] Failed to process message: {}", selfId, msg, t);
                    //获取处理失败策略
                    ProcessFailureStrategy strategy = actor.onProcessFailure(t);
                    if (strategy.isStop()) {
                        //停止 Actor
                        system.stop(selfId);
                    }
                }
            } else {
                //没有未处理消息
                noMoreElements = true;
                break;
            }
        }
        if (noMoreElements) {
            //设置空闲状态
            busy.set(FREE);
            //尝试处理队列消息（再次检查）
            dispatcher.getExecutor().execute(() -> tryProcessQueue(false));
        } else {
            //继续处理邮箱
            dispatcher.getExecutor().execute(this::processMailbox);
        }
    }

    @Override
    public TbActorId getSelf() {
        return selfId;
    }

    @Override
    public void tell(TbActorId target, TbActorMsg actorMsg) {
        system.tell(target, actorMsg);
    }

    @Override
    public void broadcastToChildren(TbActorMsg msg) {
        system.broadcastToChildren(selfId, msg);
    }

    @Override
    public void broadcastToChildrenByType(TbActorMsg msg, EntityType entityType) {
        broadcastToChildren(msg, actorId -> entityType.equals(actorId.getEntityType()));
    }

    @Override
    public void broadcastToChildren(TbActorMsg msg, Predicate<TbActorId> childFilter) {
        system.broadcastToChildren(selfId, childFilter, msg);
    }

    @Override
    public List<TbActorId> filterChildren(Predicate<TbActorId> childFilter) {
        return system.filterChildren(selfId, childFilter);
    }

    @Override
    public void stop(TbActorId target) {
        system.stop(target);
    }

    @Override
    public TbActorRef getOrCreateChildActor(TbActorId actorId, Supplier<String> dispatcher, Supplier<TbActorCreator> creator, Supplier<Boolean> createCondition) {
        TbActorRef actorRef = system.getActor(actorId);
        if (actorRef == null && createCondition.get()) {
            return system.createChildActor(dispatcher.get(), creator.get(), selfId);
        } else {
            return actorRef;
        }
    }

    public void destroy(Throwable cause) {
        if (stopReason == null) {
            stopReason = TbActorStopReason.STOPPED;
        }
        destroyInProgress.set(true);
        dispatcher.getExecutor().execute(() -> {
            try {
                ready.set(NOT_READY);
                actor.destroy(stopReason, cause);
                highPriorityMsgs.forEach(msg -> msg.onTbActorStopped(stopReason));
                normalPriorityMsgs.forEach(msg -> msg.onTbActorStopped(stopReason));
            } catch (Throwable t) {
                log.warn("[{}] Failed to destroy actor: {}", selfId, t);
            }
        });
    }

    @Override
    public TbActorId getActorId() {
        return selfId;
    }

    @Override
    public void tell(TbActorMsg actorMsg) {
        enqueue(actorMsg, NORMAL_PRIORITY);
    }

    @Override
    public void tellWithHighPriority(TbActorMsg actorMsg) {
        enqueue(actorMsg, HIGH_PRIORITY);
    }

}
