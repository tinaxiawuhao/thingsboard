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
package org.thingsboard.server.common.data.security.model;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.thingsboard.server.common.data.security.Authority;

@ApiModel(value = "JWT Pair")
@Data
@NoArgsConstructor
public class JwtPair {

    @ApiModelProperty(position = 1, value = "JWT访问令牌。用于执行API调用.", example = "AAB254FF67D..")
    private String token;
    @ApiModelProperty(position = 1, value = "JWT刷新令牌。用于在旧的JWT访问令牌过期时获取新的JWT接入令牌.", example = "AAB254FF67D..")
    private String refreshToken;

    private Authority scope;

    public JwtPair(String token, String refreshToken) {
        this.token = token;
        this.refreshToken = refreshToken;
    }

}
