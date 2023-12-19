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

import org.thingsboard.server.common.msg.TbActorMsg;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

//Actor系统
public interface TbActorSystem {

    //————————————————
    //获取执行器
    ScheduledExecutorService getScheduler();

    //创建调度器
    void createDispatcher(String dispatcherId, ExecutorService executor);

    //销毁调度器
    void destroyDispatcher(String dispatcherId);

    //获取Actor引用
    TbActorRef getActor(TbActorId actorId);

    //使用创建器创建根Actor
    TbActorRef createRootActor(String dispatcherId, TbActorCreator creator);

    //使用创建器创建指定Actor的子Actor
    TbActorRef createChildActor(String dispatcherId, TbActorCreator creator, TbActorId parent);

    //告知目标Actor消息
    void tell(TbActorId target, TbActorMsg actorMsg);

    //使用高优先级告知目标Actor消息
    void tellWithHighPriority(TbActorId target, TbActorMsg actorMsg);

    //停止指定Actor
    void stop(TbActorRef actorRef);

    //停止指定Actor
    void stop(TbActorId actorId);

    //停止系统
    void stop();

    //向指定Actor的子Actor广播消息
    void broadcastToChildren(TbActorId parent, TbActorMsg msg);

    //过滤指定Actor的子Actor后广播消息
    void broadcastToChildren(TbActorId parent, Predicate<TbActorId> childFilter, TbActorMsg msg);

    //获取指定Actor过滤后的子Actor集合
    List<TbActorId> filterChildren(TbActorId parent, Predicate<TbActorId> childFilter);
}
