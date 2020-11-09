/*
 * The MIT License
 *
 * Copyright 2020 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.server;

import com.intuit.karate.graal.VarArgsFunction;
import com.intuit.karate.StringUtils;
import com.intuit.karate.graal.JsArray;
import com.intuit.karate.graal.JsValue;
import com.intuit.karate.http.HttpUtils;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pthomas3
 */
public class HttpRequestBuilder implements ProxyObject {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestBuilder.class);

    private static final String URL = "url";
    private static final String METHOD = "method";
    private static final String PATH = "path";
    private static final String PARAM = "param";
    private static final String PARAMS = "params";
    private static final String HEADER = "header";
    private static final String HEADERS = "headers";
    private static final String BODY = "body";
    private static final String INVOKE = "invoke";
    private static final String GET = "get";
    private static final String POST = "post";
    private static final String PUT = "put";
    private static final String DELETE = "delete";
    private static final String PATCH = "patch";
    private static final String HEAD = "head";
    private static final String CONNECT = "connect";
    private static final String OPTIONS = "options";
    private static final String TRACE = "trace";

    private static final String[] KEYS = new String[]{
        URL, METHOD, PATH, PARAM, PARAMS, HEADER, HEADERS, BODY, INVOKE,
        GET, POST, PUT, DELETE, PATCH, HEAD, CONNECT, OPTIONS, TRACE
    };
    private static final Set<String> KEY_SET = new HashSet(Arrays.asList(KEYS));
    private static final JsArray KEY_ARRAY = new JsArray(KEYS);

    private String url;
    private String method;
    private List<String> paths;
    private Map<String, List<String>> params;
    private Map<String, List<String>> headers;
    private MultiPartBuilder multiPart;
    private Object body;
    private Set<Cookie> cookies;
    private String retryUntil;

    public final HttpClient client;

    public HttpRequestBuilder(HttpClient client) {
        this.client = client;
    }

    public HttpRequestBuilder reset() {
        // url and headersFactory will be retained
        method = null;
        paths = null;
        params = null;
        headers = null;
        multiPart = null;
        body = null;
        cookies = null;
        retryUntil = null;
        return this;
    }

    public Response invoke(String method) {
        this.method = method;
        return invoke();
    }

    public Response invoke(String method, Object body) {
        this.method = method;
        this.body = body;
        return invoke();
    }

    public HttpRequest build() {
        HttpRequest request = new HttpRequest();
        if (method == null) {
            if (multiPart != null && multiPart.isMultipart()) {
                method = "POST";
            } else {
                method = "GET";
            }
        }
        method = method.toUpperCase();
        request.setMethod(method);
        if ("GET".equals(method) && multiPart != null) {
            List<MultiPartBuilder.Part> parts = multiPart.getFormFields();
            if (parts != null) {
                parts.forEach(p -> param(p.getName(), (String) p.getValue()));
            }
            multiPart = null;
        }
        String urlAndPath = getUrlAndPath();
        if (params != null) {
            QueryParamsBuilder qpb = QueryParams.builder();
            params.forEach((k, v) -> qpb.add(k, v));
            String append = urlAndPath.indexOf('?') == -1 ? "?" : "&";
            urlAndPath = urlAndPath + append + qpb.toQueryString();
        }
        request.setUrl(urlAndPath);
        if (multiPart != null && body == null) { // body check is done for retry
            body = multiPart.build();
            String userContentType = getHeader(HttpConstants.HDR_CONTENT_TYPE);
            if (userContentType != null) {
                String boundary = multiPart.getBoundary();
                if (boundary != null) {
                    contentType(userContentType + "; boundary=" + boundary);
                }
            } else {
                contentType(multiPart.getContentTypeHeader());
            }
        }
        if (cookies != null && !cookies.isEmpty()) {
            List<String> cookieValues = new ArrayList(cookies.size());
            cookies.forEach(c -> cookieValues.add(ServerCookieEncoder.STRICT.encode(c)));
            header(HttpConstants.HDR_COOKIE, cookieValues);
        }
        if (body != null) {
            request.setBody(JsValue.toBytes(body));
            if (multiPart == null) {
                String contentType = getContentType();
                if (contentType == null) {
                    contentType = ResourceType.fromObject(body).contentType;
                }
                // TODO clean up http utils
                Charset charset = HttpUtils.parseContentTypeCharset(contentType);
                if (charset == null) {
                    // client can be null when not in karate scenario
                    charset = client == null ? null : client.getConfig().getCharset();
                    if (charset != null) {
                        // edge case, support setting content type to an empty string
                        contentType = StringUtils.trimToNull(contentType);
                        if (contentType != null) {
                            contentType = contentType + "; charset=" + charset;
                        }
                    }
                }
                contentType(contentType);
            }
        }
        request.setHeaders(headers);
        return request;
    }

    public Response invoke() {
        return client.invoke(build());
    }

    public boolean isRetry() {
        return retryUntil != null;
    }

    public String getRetryUntil() {
        return retryUntil;
    }

    public void setRetryUntil(String retryUntil) {
        this.retryUntil = retryUntil;
    }

    public HttpRequestBuilder url(String value) {
        url = value;
        return this;
    }

    public HttpRequestBuilder method(String method) {
        this.method = method;
        return this;
    }

    public HttpRequestBuilder path(String path) {
        if (path == null) {
            return this;
        }
        if (paths == null) {
            paths = new ArrayList<>();
        }
        paths.add(path);
        return this;
    }

    private String getPath() {
        String temp = "";
        if (paths == null) {
            return temp;
        }
        for (String path : paths) {
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (!temp.isEmpty() && !temp.endsWith("/")) {
                temp = temp + "/";
            }
            temp = temp + path;
        }
        return temp;
    }

    public String getUrlAndPath() {
        if (url == null) {
            url = "";
        }
        String path = getPath();
        if (path.isEmpty()) {
            return url;
        }
        return url.endsWith("/") ? url + path : url + "/" + path;
    }

    public HttpRequestBuilder body(Object body) {
        this.body = body;
        return this;
    }

    public List<String> getHeaderValues(String name) {
        return StringUtils.getIgnoreKeyCase(headers, name); // TODO optimize
    }

    public String getHeader(String name) {
        List<String> list = getHeaderValues(name);
        if (list == null || list.isEmpty()) {
            return null;
        } else {
            return list.get(0);
        }
    }

    public String getContentType() {
        return getHeader(HttpConstants.HDR_CONTENT_TYPE);
    }

    public HttpRequestBuilder removeHeader(String name) {
        if (headers != null) {
            StringUtils.removeIgnoreKeyCase(headers, name);
        }
        return this;
    }

    public HttpRequestBuilder header(String name, String... values) {
        return header(name, Arrays.asList(values));
    }

    public HttpRequestBuilder header(String name, List<String> values) {
        if (headers == null) {
            headers = new LinkedHashMap();
        }
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                name = key;
                break;
            }
        }
        headers.put(name, values);
        return this;
    }

    public HttpRequestBuilder header(String name, String value) {
        return header(name, Collections.singletonList(value));
    }

    public HttpRequestBuilder headers(Map<String, Object> map) {
        map.forEach((k, v) -> {
            if (!k.startsWith(":")) { // strip (armeria) special headers
                if (v instanceof List) {
                    header(k, (List) v);
                } else if (v != null) {
                    header(k, v.toString());
                }
            }
        });
        return this;
    }

    public HttpRequestBuilder headers(Value value) {
        JsValue jv = new JsValue(value);
        if (jv.isObject()) {
            headers(jv.getAsMap());
        } else {
            logger.warn("unexpected headers() argument: {}", value);
        }
        return this;
    }

    public HttpRequestBuilder contentType(String contentType) {
        if (contentType != null) {
            header(HttpConstants.HDR_CONTENT_TYPE, contentType);
        }
        return this;
    }

    public List<String> getParam(String name) {
        if (params == null || name == null) {
            return null;
        }
        return params.get(name);
    }

    public HttpRequestBuilder param(String name, String... values) {
        return param(name, Arrays.asList(values));
    }

    public HttpRequestBuilder param(String name, List<String> values) {
        if (params == null) {
            params = new HashMap();
        }
        params.put(name, values);
        return this;
    }

    public HttpRequestBuilder cookies(Collection<Map> cookies) {
        for (Map<String, Object> map : cookies) {
            cookie(map);
        }
        return this;
    }

    public HttpRequestBuilder cookie(Map<String, Object> map) {
        return cookie(Cookies.fromMap(map));
    }

    public HttpRequestBuilder cookie(Cookie cookie) {
        if (cookies == null) {
            cookies = new HashSet();
        }
        if (cookie.maxAge() != 0) { // only add cookie to request if its not already expired.
            cookies.add(cookie);
        }
        return this;
    }

    public HttpRequestBuilder cookie(String name, String value) {
        return cookie(new DefaultCookie(name, value));
    }

    public HttpRequestBuilder formField(String name, Object value) {
        if (multiPart == null) {
            multiPart = new MultiPartBuilder(false, client);
        }
        multiPart.part(name).value(value).add();
        return this;
    }

    public HttpRequestBuilder multiPart(Map<String, Object> map) {
        if (multiPart == null) {
            multiPart = new MultiPartBuilder(true, client);
        }
        multiPart.part(map);
        return this;
    }

    //==========================================================================
    //
    private final VarArgsFunction PATH_FUNCTION = args -> {
        if (args.length == 0) {
            return getPath();
        } else {
            for (Object o : args) {
                if (o != null) {
                    path(o.toString());
                }
            }
            return this;
        }
    };

    private static String toString(Object o) {
        return o == null ? null : o.toString();
    }

    private final VarArgsFunction PARAM_FUNCTION = args -> {
        if (args.length == 1) {
            List<String> list = getParam(toString(args[0]));
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.get(0);
        } else {
            param(toString(args[0]), toString(args[1]));
            return this;
        }
    };

    private final VarArgsFunction HEADER_FUNCTION = args -> {
        if (args.length == 1) {
            return getHeader(toString(args[0]));
        } else {
            header(toString(args[0]), toString(args[1]));
            return this;
        }
    };

    private final VarArgsFunction INVOKE_FUNCTION = args -> {
        switch (args.length) {
            case 0:
                return invoke();
            case 1:
                return invoke(args[0].toString());
            default:
                return invoke(args[0].toString(), args[1]);
        }
    };

    private final VarArgsFunction METHOD_FUNCTION = args -> {
        if (args.length > 0) {
            return method((String) args[0]);
        } else {
            return method;
        }
    };

    private final VarArgsFunction BODY_FUNCTION = args -> {
        if (args == null) { // can be null
            return this;
        }
        if (args.length > 0) {
            return body(args[0]);
        } else {
            return JsValue.fromJava(body);
        }
    };

    private final Supplier GET_FUNCTION = () -> invoke(GET);
    private final Function POST_FUNCTION = o -> invoke(POST, o);
    private final Function PUT_FUNCTION = o -> invoke(PUT, o);
    private final Function PATCH_FUNCTION = o -> invoke(PATCH, o);
    private final Supplier DELETE_FUNCTION = () -> invoke(DELETE);

    @Override
    public Object getMember(String key) {
        switch (key) {
            case METHOD:
                return METHOD_FUNCTION;
            case PATH:
                return PATH_FUNCTION;
            case HEADER:
                return HEADER_FUNCTION;
            case HEADERS:
                return JsValue.fromJava(headers);
            case PARAM:
                return PARAM_FUNCTION;
            case PARAMS:
                return JsValue.fromJava(params);
            case BODY:
                return BODY_FUNCTION;
            case INVOKE:
                return INVOKE_FUNCTION;
            case GET:
                return GET_FUNCTION;
            case POST:
                return POST_FUNCTION;
            case PUT:
                return PUT_FUNCTION;
            case PATCH:
                return PATCH_FUNCTION;
            case DELETE:
                return DELETE_FUNCTION;
            case URL:
                return (Function<String, Object>) this::url;
            default:
                logger.warn("no such property on http object: {}", key);
                return null;
        }
    }

    @Override
    public void putMember(String key, Value value) {
        switch (key) {
            case METHOD:
                method = value.asString();
                break;
            case BODY:
                body = JsValue.toJava(value);
                break;
            case HEADERS:
                headers(value);
                break;
            case PARAMS:
                params = (Map) JsValue.toJava(value);
                break;
            case URL:
                url = value.asString();
                break;
            default:
                logger.warn("put not supported on http object: {} - {}", key, value);
        }
    }

    @Override
    public Object getMemberKeys() {
        return KEY_ARRAY;
    }

    @Override
    public boolean hasMember(String key) {
        return KEY_SET.contains(key);
    }

    @Override
    public String toString() {
        return getUrlAndPath();
    }

}