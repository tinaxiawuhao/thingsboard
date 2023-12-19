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
import org.thingsboard.server.common.msg.TbActorStopReason;

public interface TbActor {

    //处理消息
    boolean process(TbActorMsg msg);

    //获取引用
    TbActorRef getActorRef();

    //初始化方法，默认空实现
    default void init(TbActorCtx ctx) throws TbActorException {
    }

    //销毁方法，默认空实现
    default void destroy(TbActorStopReason stopReason, Throwable cause) throws TbActorException {
    }

    //获取初始化失败策略
    //策略为停止或延迟（可选）重试
    default InitFailureStrategy onInitFailure(int attempt, Throwable t) {
        return InitFailureStrategy.retryWithDelay(5000L * attempt);
    }

    //获取处理消息失败策略
    //策略为停止或继续
    default ProcessFailureStrategy onProcessFailure(Throwable t) {
        if (t instanceof Error) {
            return ProcessFailureStrategy.stop();
        } else {
            return ProcessFailureStrategy.resume();
        }
    }
}
