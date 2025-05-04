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

import io.qameta.allure.Allure;
import io.qameta.allure.attachment.AttachmentData;
import io.qameta.allure.attachment.AttachmentProcessor;
import io.qameta.allure.attachment.AttachmentRenderer;
import io.qameta.allure.attachment.DefaultAttachmentProcessor;
import io.qameta.allure.attachment.FreemarkerAttachmentRenderer;
import io.qameta.allure.attachment.http.HttpRequestAttachment;
import io.qameta.allure.attachment.http.HttpResponseAttachment;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Interceptor for Spring's HTTP clients that adds Allure attachments and steps for HTTP requests/responses.
 *
 * <p>This interceptor captures HTTP communication details and attaches them to Allure reports with rich formatting.
 * It supports both request/response attachments and structured test steps with status tracking.</p>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Customizable request/response rendering via Freemarker templates</li>
 *   <li>Automatic step creation with status updates</li>
 *   <li>Header/body processing and prettification</li>
 *   <li>Flexible configuration through builder pattern</li>
 * </ul>
 *
 * <h3>Example Usage with RestTemplate:</h3>
 * <pre>{@code
 * RestTemplate restTemplate = new RestTemplate();
 * restTemplate.getInterceptors().add(AllureClientHttpRequestInterceptor.builder()
 *     .enableSteps(true)
 *     .stepNameExtractor(request -> request.getMethod() + " /api/endpoint")
 *     .requestTemplatePath("http-request.ftl")
 *     .responseTemplatePath("http-response.ftl")
 *     .build());
 * }</pre>
 *
 * <h3>Example Usage with RestClient:</h3>
 * <pre>{@code
 * RestClient restClient = RestClient.builder()
 *     .requestInterceptor(AllureClientHttpRequestInterceptor.builder()
 *         .requestAttachmentNameExtractor((httpRequest, requestBody) -> "API Request")
 *         .responseAttachmentNameExtractor((clientHttpResponse, responseBody) -> "API Response")
 *         .build())
 *     .build();
 * }</pre>
 *
 * <p>For template customization, place Freemarker templates in your resources folder (e.g., src/test/resources/tpl).
 * </p>
 */
public final class AllureClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {
    private AttachmentRenderer<AttachmentData> requestRenderer;
    private AttachmentRenderer<AttachmentData> responseRenderer;
    private BiFunction<HttpRequest, String, String> requestBodyPrettifier;
    private BiFunction<ClientHttpResponse, String, String> responseBodyPrettifier;
    private BiFunction<HttpRequest, String, String> requestAttachmentNameExtractor;
    private BiFunction<ClientHttpResponse, String, String> responseAttachmentNameExtractor;
    private Supplier<AttachmentProcessor<AttachmentData>> attachmentProcessorSupplier;
    private Function<MultiValueMap<String, String>, Map<String, String>> headersExtractor;
    private boolean enableSteps;
    private BiFunction<HttpRequest, String, String> stepNameExtractor;
    private BiFunction<ClientHttpResponse, String, Status> stepStatusExtractor;

    /**
     * Private default constructor in case of builder usage.
     */
    private AllureClientHttpRequestInterceptor() {
    }

    /**
     * Convenient builder way to create interceptor.
     * @return AllureClientHttpRequestInterceptor.Builder
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder
     */
    public static AllureClientHttpRequestInterceptor.Builder builder() {
        return new Builder();
    }

    /**
     * Renderer for HTTP request attachments using Freemarker templates.
     * Default template path: "http-request.ftl"
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#requestTemplatePath
     */
    void setRequestRenderer(final AttachmentRenderer<AttachmentData> requestRenderer) {
        this.requestRenderer = requestRenderer;
    }

    /**
     * Renderer for HTTP response attachments using Freemarker templates.
     * Default template path: "http-response.ftl"
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#responseTemplatePath
     */
    void setResponseRenderer(final AttachmentRenderer<AttachmentData> responseRenderer) {
        this.responseRenderer = responseRenderer;
    }

    /**
     * Function to prettify HTTP request bodies before attaching to Allure.
     * Default: returns raw body string.
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#requestBodyPrettifier
     */
    void setRequestBodyPrettifier(final BiFunction<HttpRequest, String, String> requestBodyPrettifier) {
        this.requestBodyPrettifier = requestBodyPrettifier;
    }

    /**
     * Function to prettify HTTP response bodies before attaching to Allure.
     * Default: returns raw body string.
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#responseBodyPrettifier
     */
    void setResponseBodyPrettifier(final BiFunction<ClientHttpResponse, String, String> responseBodyPrettifier) {
        this.responseBodyPrettifier = responseBodyPrettifier;
    }

    /**
     * Extractor for HTTP request attachment names.
     * Default: always returns "Request".
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#requestAttachmentNameExtractor
     */
    void setRequestAttachmentNameExtractor(
            final BiFunction<HttpRequest, String, String> requestAttachmentNameExtractor) {
        this.requestAttachmentNameExtractor = requestAttachmentNameExtractor;
    }

    /**
     * Extractor for HTTP response attachment names.
     * Default: always returns "Response".
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#responseAttachmentNameExtractor
     */
    void setResponseAttachmentNameExtractor(
            final BiFunction<ClientHttpResponse, String, String> responseAttachmentNameExtractor) {
        this.responseAttachmentNameExtractor = responseAttachmentNameExtractor;
    }

    /**
     * Supplier for creating new attachment processors.
     * Default: uses Allure's default processor.
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#attachmentProcessorSupplier(Supplier)
     */
    void setAttachmentProcessorSupplier(
            final Supplier<AttachmentProcessor<AttachmentData>> attachmentProcessorSupplier) {
        this.attachmentProcessorSupplier = attachmentProcessorSupplier;
    }

    /**
     * Extractor for converting HTTP headers to Map for Allure attachments.
     * Default: converts headers to single-value map with joined values.
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#headersExtractor(Function)
     */
    void setHeadersExtractor(final Function<MultiValueMap<String, String>, Map<String, String>> headersExtractor) {
        this.headersExtractor = headersExtractor;
    }

    /**
     * Flag to enable/disable Allure step tracking.
     * Default: true.
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#enableSteps(boolean)
     */
    void setEnableSteps(final boolean enableSteps) {
        this.enableSteps = enableSteps;
    }

    /**
     * Extractor for generating Allure step names from HTTP requests.
     * Default: "{HTTP_METHOD} {URI}" (e.g., "GET /api/users").
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#stepNameExtractor(BiFunction)
     */
    void setStepNameExtractor(final BiFunction<HttpRequest, String, String> stepNameExtractor) {
        this.stepNameExtractor = stepNameExtractor;
    }

    /**
     * Extractor for determining Allure step status from HTTP responses.
     * Default: always returns PASSED.
     * @see io.qameta.allure.springweb.AllureClientHttpRequestInterceptor.Builder#stepStatusExtractor(BiFunction)
     */
    void setStepStatusExtractor(final BiFunction<ClientHttpResponse, String, Status> stepStatusExtractor) {
        this.stepStatusExtractor = stepStatusExtractor;
    }

    /**
     * Intercepts HTTP requests and responses to add Allure attachments and steps.
     *
     * <p>Processing flow:</p>
     * <ol>
     *   <li>Starts a new Allure step if step tracking is enabled</li>
     *   <li>Captures request details and creates attachment</li>
     *   <li>Executes the actual HTTP request</li>
     *   <li>Captures response details and creates attachment</li>
     *   <li>Updates and completes the Allure step with response status</li>
     * </ol>
     *
     * @param request the HTTP request
     * @param body the request body
     * @param execution the request execution chain
     * @return the HTTP response
     * @throws IOException if an I/O error occurs
     */
    @Override
    @SuppressWarnings("NullableProblems")
    public ClientHttpResponse intercept(@NonNull final HttpRequest request,
                                        final byte[] body,
                                        @NonNull final ClientHttpRequestExecution execution) throws IOException {
        final String stepUUID = UUID.randomUUID().toString();
        final AttachmentProcessor<AttachmentData> processor = attachmentProcessorSupplier.get();

        final String requestBody = extractRequestBody(request, body, requestBodyPrettifier);
        if (enableSteps) {
            startStep(stepUUID, request, requestBody);
        }
        addRequestAttachment(processor, request, requestBody);

        final ClientHttpResponse clientHttpResponse = execution.execute(request, body);

        final String responseBody = extractResponseBody(clientHttpResponse, responseBodyPrettifier);
        addResponseAttachment(processor, clientHttpResponse, responseBody);
        if (enableSteps) {
            updateAndStopStep(stepUUID, clientHttpResponse, responseBody);
        }

        return clientHttpResponse;
    }

    /**
     * Converts multi-value headers map to single-value map for Allure display.
     * <p>Current implementation joins multiple header values with "; "</p>
     */
    static Map<String, String> toMapConverter(final Map<String, List<String>> items) {
        final Map<String, String> result = new HashMap<>();
        items.forEach((key, value) -> result.put(key, String.join("; ", value)));
        return result;
    }

    /**
     * Starts a new Allure step with the given request.
     */
    private void startStep(final String stepUUID, final HttpRequest request, final String body) {
        Allure.getLifecycle().startStep(stepUUID, new StepResult()
                .setName(stepNameExtractor.apply(request, body))
                .setStatus(Status.PASSED));
    }

    /**
     * Adds request details as an Allure attachment.
     */
    private void addRequestAttachment(final AttachmentProcessor<AttachmentData> processor,
                                      final HttpRequest request,
                                      final String requestBody) {
        final HttpRequestAttachment requestAttachment = HttpRequestAttachment.Builder
                .create(requestAttachmentNameExtractor.apply(request, requestBody), request.getURI().toString())
                .setMethod(request.getMethod().name())
                .setHeaders(headersExtractor.apply(request.getHeaders()))
                .setBody(requestBody)
                .build();
        processor.addAttachment(requestAttachment, requestRenderer);
    }

    /**
     * Extracts and formats the request body for Allure attachment.
     */
    private String extractRequestBody(final HttpRequest request,
                                      final byte[] body,
                                      final BiFunction<HttpRequest, String, String> prettifier) {
        if (body == null || body.length == 0) {
            return "";
        }
        final String requestBody = new String(body, StandardCharsets.UTF_8);
        return prettifier.apply(request, requestBody);
    }

    /**
     * Extracts and formats the response body for Allure attachment.
     */
    private String extractResponseBody(final ClientHttpResponse response,
                                       final BiFunction<ClientHttpResponse, String, String> prettifier
    ) throws IOException {
        final String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        return prettifier.apply(response, responseBody);
    }

    /**
     * Adds response details as an Allure attachment.
     */
    private void addResponseAttachment(
            final AttachmentProcessor<AttachmentData> processor,
            final ClientHttpResponse response,
            final String responseBody) throws IOException {
        final HttpResponseAttachment httpResponseAttachment = HttpResponseAttachment.Builder
                .create(responseAttachmentNameExtractor.apply(response, responseBody))
                .setResponseCode(response.getStatusCode().value())
                .setHeaders(headersExtractor.apply(response.getHeaders()))
                .setBody(responseBody)
                .build();
        processor.addAttachment(httpResponseAttachment, responseRenderer);
    }

    /**
     * Updates Allure step status and completes the step.
     */
    private void updateAndStopStep(final String stepUUID,
                                   final ClientHttpResponse response,
                                   final String responseBody) {
        Allure.getLifecycle().updateStep(stepUUID, step ->
                step.setStatus(stepStatusExtractor.apply(response, responseBody)));
        Allure.getLifecycle().stopStep(stepUUID);
    }

    /**
     * Builder class for configuring AllureClientHttpRequestInterceptor instances.
     *
     * <h3>Configuration Examples:</h3>
     *
     * <p>Custom JSON prettification:</p>
     * <pre>{@code
     * AllureClientHttpRequestInterceptor.builder()
     *      .requestBodyPrettifier((req, body) -> new Gson().toJson(new JsonParser().parse(body)))
     *      .build()
     * }</pre>
     *
     * <p>Custom header filtering:</p>
     * <pre>{@code
     * AllureClientHttpRequestInterceptor.builder()
     *      .headersExtractor(headers -> headers.toSingleValueMap().entrySet().stream()
     *          .filter(entry -> entry.getKey().startsWith("X-"))
     *          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
     *      .build()
     * }</pre>
     */
    public static class Builder {
        private String requestTemplatePath = "http-request.ftl";
        private String responseTemplatePath = "http-response.ftl";
        private BiFunction<HttpRequest, String, String> requestBodyPrettifier =
                (httpRequest, requestBody) -> requestBody;
        private BiFunction<ClientHttpResponse, String, String> responseBodyPrettifier =
                (clientHttpResponse, responseBody) -> responseBody;
        private BiFunction<HttpRequest, String, String> requestAttachmentNameExtractor =
                (httpRequest, requestBody) -> "Request";
        private BiFunction<ClientHttpResponse, String, String> responseAttachmentNameExtractor =
                (clientHttpResponse, responseBody) -> "Response";
        private Supplier<AttachmentProcessor<AttachmentData>> attachmentProcessorSupplier =
                DefaultAttachmentProcessor::new;
        private Function<MultiValueMap<String, String>, Map<String, String>> headersExtractor =
                AllureClientHttpRequestInterceptor::toMapConverter;
        private boolean enableSteps = true;
        private BiFunction<HttpRequest, String, String> stepNameExtractor =
                (httpRequest, requestBody) -> httpRequest.getMethod() + " " + httpRequest.getURI();
        private BiFunction<ClientHttpResponse, String, Status> stepStatusExtractor =
                (clientHttpResponse, responseBody) -> Status.PASSED;

        /**
         * Protected default constructor for builder pattern.
         */
        protected Builder() {
            // Protected default constructor for builder pattern.
        }

        /**
         * Builds the configured interceptor instance.
         *
         * @return the configured interceptor
         */
        public AllureClientHttpRequestInterceptor build() {
            final AllureClientHttpRequestInterceptor instance = new AllureClientHttpRequestInterceptor();
            instance.setRequestRenderer(new FreemarkerAttachmentRenderer(requestTemplatePath));
            instance.setResponseRenderer(new FreemarkerAttachmentRenderer(responseTemplatePath));
            instance.setRequestBodyPrettifier(requestBodyPrettifier);
            instance.setResponseBodyPrettifier(responseBodyPrettifier);
            instance.setRequestAttachmentNameExtractor(requestAttachmentNameExtractor);
            instance.setResponseAttachmentNameExtractor(responseAttachmentNameExtractor);
            instance.setAttachmentProcessorSupplier(attachmentProcessorSupplier);
            instance.setHeadersExtractor(headersExtractor);
            instance.setEnableSteps(enableSteps);
            instance.setStepNameExtractor(stepNameExtractor);
            instance.setStepStatusExtractor(stepStatusExtractor);
            return instance;
        }

        /**
         * Sets the Freemarker template path for request rendering in resources like /src/test/resources/tpl/.
         *
         * <p><b>Default:</b> "http-request.ftl"</p>
         *
         * <p>Example for template with path /src/test/resources/tpl/custom-request-template.ftl
         * <pre>{@code
         * .requestTemplatePath("custom-request-template.ftl")
         * }</pre></p>
         *
         * @param requestTemplatePath path to template file
         * @return this builder instance
         * @throws IllegalArgumentException if requestTemplatePath is null
         */
        public AllureClientHttpRequestInterceptor.Builder requestTemplatePath(final String requestTemplatePath) {
            Assert.notNull(requestTemplatePath, "Request template path must not be null");
            this.requestTemplatePath = requestTemplatePath;
            return this;
        }

        /**
         * Sets the Freemarker template path for response rendering in resources like /src/test/resources/tpl/.
         *
         * <p><b>Default:</b> "http-response.ftl"</p>
         *
         * <p>Example for template with path /src/test/resources/tpl/custom-response-template.ftl
         * <pre>{@code
         * .responseTemplatePath("custom-response-template.ftl")
         * }</pre></p>
         *
         * @param responseTemplatePath path to template file
         * @return this builder instance
         * @throws IllegalArgumentException if responseTemplatePath is null
         */
        public AllureClientHttpRequestInterceptor.Builder responseTemplatePath(final String responseTemplatePath) {
            Assert.notNull(responseTemplatePath, "Response template path must not be null");
            this.responseTemplatePath = responseTemplatePath;
            return this;
        }

        /**
         * Sets the request body prettifier function. HttpRequest provides to function in case of to define by
         * headers or URL what the format request body actually have.
         *
         * <p><b>Default:</b> Returns raw request body as it is</p>
         *
         * <p><b>Example:</b>
         * <pre>{@code
         * .requestBodyPrettifier((httpRequest, requestBody) -> new Gson().toJson(new JsonParser().parse(requestBody)))
         * }</pre></p>
         *
         * @param requestBodyPrettifier function to format request bodies
         * @return this builder instance
         * @throws IllegalArgumentException if requestBodyPrettifier is null
         */
        public AllureClientHttpRequestInterceptor.Builder requestBodyPrettifier(
                final BiFunction<HttpRequest, String, String> requestBodyPrettifier) {
            Assert.notNull(requestBodyPrettifier, "Request body prettifier must not be null");
            this.requestBodyPrettifier = requestBodyPrettifier;
            return this;
        }

        /**
         * Sets the response body prettifier function. ClientHttpResponse provides to function in case of to define by
         * headers or URL what the format response body actually have.
         *
         * <p><b>Default:</b> Returns response body as it is</p>
         *
         * <p>Example for format json request body with Gson:
         * <pre>{@code
         * .responseBodyPrettifier((clientHttpResponse, responseBody) ->
         *      new Gson().toJson(new JsonParser().parse(responseBody)))
         * }</pre></p>
         *
         * @param responseBodyPrettifier function to format response bodies
         * @return this builder instance
         * @throws IllegalArgumentException if responseBodyPrettifier is null
         */
        public AllureClientHttpRequestInterceptor.Builder responseBodyPrettifier(
                final BiFunction<ClientHttpResponse, String, String> responseBodyPrettifier) {
            Assert.notNull(responseBodyPrettifier, "Response body prettifier must not be null");
            this.responseBodyPrettifier = responseBodyPrettifier;
            return this;
        }

        /**
         * Sets the request attachment file name extractor.
         *
         * <p><b>Default:</b> "Request"</p>
         *
         * <p>Example of editing the Allure attachment name based on the request data, namely the url resource
         * without specifying the host:
         * <pre>{@code
         * .requestAttachmentNameExtractor(req -> "Request on path " + req.getURI().getPath())
         * }</pre></p>
         *
         * @param requestAttachmentNameExtractor function to extract attachment names
         * @return this builder instance
         * @throws IllegalArgumentException if requestAttachmentNameExtractor is null
         */
        public AllureClientHttpRequestInterceptor.Builder requestAttachmentNameExtractor(
                final BiFunction<HttpRequest, String, String> requestAttachmentNameExtractor) {
            Assert.notNull(responseBodyPrettifier, "Request attachment extractor must not be null");
            this.requestAttachmentNameExtractor = requestAttachmentNameExtractor;
            return this;
        }

        /**
         * Sets the response attachment file name extractor.
         *
         * <p><b>Default:</b> "Response"</p>
         * <pre>{@code
         * .responseAttachmentNameExtractor((response, responseBody) -> "Response")
         * }</pre>
         *
         * <p>Example of determining the attachment name from a specific response header:
         * <pre>{@code
         * .responseAttachmentNameExtractor((response, responseBody) ->
         *         "Response with traceId: " + response.getHeaders().getFirst("X-B3-TraceId"))
         * }</pre></p>
         *
         * @param responseAttachmentNameExtractor function to extract attachment names
         * @return this builder instance
         * @throws IllegalArgumentException if responseAttachmentNameExtractor is null
         */
        public AllureClientHttpRequestInterceptor.Builder responseAttachmentNameExtractor(
                final BiFunction<ClientHttpResponse, String, String> responseAttachmentNameExtractor) {
            Assert.notNull(responseBodyPrettifier, "Response attachment extractor must not be null");
            this.responseAttachmentNameExtractor = responseAttachmentNameExtractor;
            return this;
        }

        /**
         * Sets the attachment processor supplier.
         *
         * <p><b>Default:</b> Uses {@link io.qameta.allure.attachment.DefaultAttachmentProcessor}</p>
         *
         * @param attachmentProcessorSupplier supplier for attachment processors
         * @return this builder instance
         * @throws IllegalArgumentException if attachmentProcessorSupplier is null
         */
        public AllureClientHttpRequestInterceptor.Builder attachmentProcessorSupplier(
                final Supplier<AttachmentProcessor<AttachmentData>> attachmentProcessorSupplier) {
            Assert.notNull(responseBodyPrettifier, "Attachment processor supplier must not be null");
            this.attachmentProcessorSupplier = attachmentProcessorSupplier;
            return this;
        }

        /**
         * Sets the header extraction function.
         *
         * <p><b>Default:</b> Converts HttpHeaders to single-value map with joined values</p>
         *
         * <p><b>Example:</b>
         * <pre>{@code
         * .headersExtractor(headers -> headers.toSingleValueMap())
         * }</pre></p>
         *
         * @param headersExtractor function to convert headers to displayable format
         * @return this builder instance
         * @throws IllegalArgumentException if headersExtractor is null
         */
        public AllureClientHttpRequestInterceptor.Builder headersExtractor(
                final Function<MultiValueMap<String, String>, Map<String, String>> headersExtractor) {
            Assert.notNull(responseBodyPrettifier, "Headers extractor supplier must not be null");
            this.headersExtractor = headersExtractor;
            return this;
        }

        /**
         * Enables or disables Allure step's creation for each request.
         *
         * <p><b>Default:</b> true</p>
         *
         * @param enableSteps true to enable step tracking
         * @return this builder instance
         */
        public AllureClientHttpRequestInterceptor.Builder enableSteps(final boolean enableSteps) {
            this.enableSteps = enableSteps;
            return this;
        }

        /**
         * Sets the step name extractor function.
         *
         * <p><b>Default:</b> "{HTTP_METHOD} {URI}" (e.g., "GET /api/users")</p>
         *
         * <p><b>Example:</b>
         * <pre>{@code
         * .stepNameExtractor(req -> req.getMethod() + " " + req.getURI().getPath())
         * }</pre></p>
         *
         * @param stepNameExtractor function to generate step names from requests
         * @return this builder instance
         * @throws IllegalArgumentException if stepNameExtractor is null
         */
        public AllureClientHttpRequestInterceptor.Builder stepNameExtractor(
                final BiFunction<HttpRequest, String, String> stepNameExtractor) {
            Assert.notNull(stepNameExtractor, "Step name extractor must not be null");
            this.stepNameExtractor = stepNameExtractor;
            return this;
        }

        /**
         * Sets the step status extractor function.
         *
         * <p><b>Default:</b> Always returns PASSED, since the interceptor does not know whether a negative or
         * positive scenario is being checked.</p>
         * <pre>{@code
         * .stepStatusExtractor((response, responseBody) -> Status.PASSED)
         * }</pre>
         *
         * <p>This extractor provides the ability to fine-tune the Allure step status based on the response code,
         * response headers, and response body data from the service.</p>
         *
         * <p>Example of determining the result of a step based on the response status of a message:
         * <pre>{@code
         * .stepStatusExtractor((response, responseBody) ->
         *      res.getStatusCode().is2xxSuccessful() ? Status.PASSED : Status.FAILED)
         * }</pre></p>
         *
         * <p>More complicated example of determining the result of a step based on the body of the response from
         * the service and the specific response header:
         * <pre>{@code
         * .stepStatusExtractor((response, responseBody) -> {
         *     final String testHeader = response.getHeaders().getFirst("X-User-Agent");
         *     final boolean isTestUserAgent = testHeader != null && testHeader.contains("test");
         *     final boolean bodyWithError = responseBody.contains("exception") || responseBody.contains("error");
         *     if (isTestUserAgent && bodyWithError) {
         *         try {
         *             return response.getStatusCode().is2xxSuccessful() ? Status.PASSED : Status.FAILED;
         *         } catch (IOException ignored) {
         *             return Status.BROKEN;
         *         }
         *     } else {
         *         return Status.PASSED;
         *     }
         * })
         * }</pre></p>
         *
         * @param stepStatusExtractor function to determine step status from responses
         * @return this builder instance
         * @throws IllegalArgumentException if stepStatusExtractor is null
         */
        public AllureClientHttpRequestInterceptor.Builder stepStatusExtractor(
                final BiFunction<ClientHttpResponse, String, Status> stepStatusExtractor) {
            Assert.notNull(stepStatusExtractor, "Step status extractor must not be null");
            this.stepStatusExtractor = stepStatusExtractor;
            return this;
        }
    }
}
