/**
 * Copyright 2021 Shulie Technology, Co.Ltd
 * Email: shulie@shulie.io
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pamirs.attach.plugin.springjms;

import com.pamirs.pradar.MiddlewareType;

/**
 * @author xiaobin.zfb|xiaobin@shulie.io
 * @since 2020/10/19 3:20 下午
 */
public final class SpringJmsConstants {
    public final static String PLUGIN_NAME = "spring-jms";
    public final static int PLUGIN_TYPE = MiddlewareType.TYPE_MQ;
    public final static String MODULE_NAME = "spring-jms";

    public final static String REFLECT_FIELD_HEADERS = "headers";
}
