/*
 *  Copyright 2016-2024 Qameta Software Inc
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.qameta.allure.springweb;

import io.qameta.allure.attachment.FreemarkerAttachmentRenderer;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.IOException;

/**
 * Allure interceptor for spring rest template.
 * Do not use AllureRestTemplate. Use {@link io.qameta.allure.springweb.AllureClientHttpRequestInterceptor} for
 * RestTemplate, TestRestTemplate and RestClient.
 *
 * @deprecated in case of more configurable implementation
 * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor
 */
@Deprecated
public class AllureRestTemplate implements ClientHttpRequestInterceptor {
    private final AllureClientHttpRequestInterceptor interceptor = AllureClientHttpRequestInterceptor.builder()
            .enableSteps(false)
            .build();

    @Deprecated
    public AllureRestTemplate setRequestTemplate(final String templatePath) {
        interceptor.setRequestRenderer(new FreemarkerAttachmentRenderer(templatePath));
        return this;
    }

    @Deprecated
    public AllureRestTemplate setResponseTemplate(final String templatePath) {
        interceptor.setResponseRenderer(new FreemarkerAttachmentRenderer(templatePath));
        return this;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public ClientHttpResponse intercept(@NonNull final HttpRequest request, final byte[] body,
                                        @NonNull final ClientHttpRequestExecution execution) throws IOException {
        return interceptor.intercept(request, body, execution);
    }
}
