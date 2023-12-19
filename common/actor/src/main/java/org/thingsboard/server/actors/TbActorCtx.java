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

import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.msg.TbActorMsg;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

//Actor上下文接口
public interface TbActorCtx extends TbActorRef {

    //获取自身标识
    TbActorId getSelf();

    //获取父引用
    TbActorRef getParentRef();

    //告知目标Actor消息
    void tell(TbActorId target, TbActorMsg msg);

    //停止目标Actor
    void stop(TbActorId target);

    //获取或创建子Actor
    TbActorRef getOrCreateChildActor(TbActorId actorId, Supplier<String> dispatcher, Supplier<TbActorCreator> creator, Supplier<Boolean> createCondition);

    //向子Actor广播消息
    void broadcastToChildren(TbActorMsg msg);

    //向指定实体类型的子Actor广播消息
    void broadcastToChildrenByType(TbActorMsg msg, EntityType entityType);

    //向符合过滤器的子Actor广播消息
    void broadcastToChildren(TbActorMsg msg, Predicate<TbActorId> childFilter);

    //获取过滤后的子Actor集合
    List<TbActorId> filterChildren(Predicate<TbActorId> childFilter);
}
