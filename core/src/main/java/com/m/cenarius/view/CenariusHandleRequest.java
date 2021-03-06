package com.m.cenarius.view;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;

import com.alibaba.fastjson.JSON;
import com.m.cenarius.Constants;
import com.m.cenarius.resourceproxy.cache.AssetCache;
import com.m.cenarius.resourceproxy.cache.CacheEntry;
import com.m.cenarius.resourceproxy.cache.InternalCache;
import com.m.cenarius.resourceproxy.network.InterceptJavascriptInterface;
import com.m.cenarius.route.Route;
import com.m.cenarius.route.RouteManager;
import com.m.cenarius.utils.MimeUtils;
import com.m.cenarius.utils.OpenApiTracker;
import com.m.cenarius.utils.QueryUtil;

import org.xutils.common.Callback;
import org.xutils.http.HttpMethod;
import org.xutils.http.RequestParams;
import org.xutils.x;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Map;

/**
 * 处理拦截逻辑
 */

public class CenariusHandleRequest {

    public static boolean handleWidgets(View view, String url, List<CenariusWidget> widgets) {
        if (url.startsWith(Constants.CONTAINER_WIDGET_BASE)) {
            boolean handled;
            for (CenariusWidget widget : widgets) {
                if (null != widget) {
                    handled = widget.handle(view, url);
                    if (handled) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static WebResourceResponse handleResourceRequest(String requestUrl) {
        String fileExtension = MimeTypeMap.getFileExtensionFromUrl(requestUrl);
        String mimeType = MimeUtils.guessMimeTypeFromExtension(fileExtension);
        String uriString = uriForUrl(requestUrl);
        if (uriString != null) {
            // requestUrl 符合拦截规则
            Uri finalUri = Uri.parse(uriString);
            String baseUri = finalUri.getPath();
            RouteManager routeManager = RouteManager.getInstance();
            CacheEntry cacheEntry;
            if (routeManager.isInWhiteList(baseUri)) {
                // 白名单 缓存
                cacheEntry = AssetCache.getInstance().findWhiteListCache(baseUri);
                return new WebResourceResponse(mimeType, "UTF-8", cacheEntry.inputStream);
            } else {
                Route route = RouteManager.getInstance().findRoute(baseUri);
                if (route != null) {
                    // cache 缓存
                    cacheEntry = InternalCache.getInstance().findCache(route);
                    if (cacheEntry == null) {
                        // asset 缓存
                        cacheEntry = AssetCache.getInstance().findCache(route);
                    }
                    if (null != cacheEntry && cacheEntry.isValid()) {
                        return new WebResourceResponse(mimeType, "UTF-8", cacheEntry.inputStream);
                    }

                    // 从网络加载
                    try {
                        Log.v("cenarius", "start load h5 :" + requestUrl);
                        final PipedOutputStream out = new PipedOutputStream();
                        final PipedInputStream in = new PipedInputStream(out);
                        WebResourceResponse xResponse = new WebResourceResponse(mimeType, "UTF-8", in);
                        loadResourceRequest(route, out);
                        return xResponse;
                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.e("cenarius", "url : " + requestUrl + " " + e.getMessage());
                    }
                }
            }
        }

        return null;
    }

    public static WebResourceResponse handleAjaxRequest(String requestUrl, InterceptJavascriptInterface.AjaxRequestContents ajaxRequestContents) {
        // header
        Map header = JSON.parseObject(ajaxRequestContents.header, Map.class);
        if (header.get("X-Requested-With").equals("OpenAPIRequest")) {
            String query = Uri.parse(requestUrl).getQuery();
            if (query == null || QueryUtil.queryMap(query).get("sign") == null) {
                // 需要签名
                String body = ajaxRequestContents.body;
                String fileExtension = MimeTypeMap.getFileExtensionFromUrl(requestUrl);
                String mimeType = MimeUtils.guessMimeTypeFromExtension(fileExtension);
                // 从网络加载
                try {
                    Log.v("cenarius", "start load ajax :" + requestUrl);
                    final PipedOutputStream out = new PipedOutputStream();
                    final PipedInputStream in = new PipedInputStream(out);
                    WebResourceResponse xResponse = new WebResourceResponse(mimeType, "UTF-8", in);
//                    // 把带参数的 uri 给到加载
//                    final String url = OpenApi.openApiQuery(query, body);
                    loadAjaxRequest(ajaxRequestContents.method, requestUrl, ajaxRequestContents.header, ajaxRequestContents.body, out);
                    return xResponse;
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e("cenarius", "url : " + requestUrl + " " + e.getMessage());
                }
            }
        }


        return null;
    }

    private static void loadAjaxRequest(String method, String url, String header, String body, final PipedOutputStream outputStream) {
        method = method.toUpperCase();
        HttpMethod httpMethod;
        if (method.equals("DELETE")) {
            httpMethod = HttpMethod.DELETE;
        } else if (method.equals("POST")) {
            httpMethod = HttpMethod.POST;
        } else if (method.equals("PUT")) {
            httpMethod = HttpMethod.PUT;
        } else {
            httpMethod = HttpMethod.GET;
        }

        // 由于 xutils 不能自动从 url？ 后面取出参数，这里手动取出
        RequestParams requestParams = new RequestParams(QueryUtil.baseUrlFromUrl(url));
        QueryUtil.addQueryForRequestParams(requestParams, url);
        // 设置 OpenApi 拦截器
        requestParams.setRequestTracker(new OpenApiTracker());

        if (header != null) {
            Map<String, String> map = JSON.parseObject(header, Map.class);
            for (String key : map.keySet()) {
                String value = map.get(key);
                requestParams.addHeader(key, value);
            }
        }

        if (body != null) {
            Map<String, List<String>> map = QueryUtil.queryMap(body);
            for (String key : map.keySet()) {
                String value = map.get(key).get(0);
                requestParams.addBodyParameter(key, value);
            }
        }

        x.http().request(httpMethod, requestParams, new Callback.CommonCallback<byte[]>() {
            @Override
            public void onSuccess(byte[] result) {
                writeOutputStream(outputStream, result);
            }

            @Override
            public void onError(Throwable ex, boolean isOnCallback) {
                byte[] result = wrapperErrorThrowable(ex);
                writeOutputStream(outputStream, result);
            }

            @Override
            public void onCancelled(CancelledException cex) {

            }

            @Override
            public void onFinished() {

            }
        });
    }

    private static void loadResourceRequest(final Route route, final PipedOutputStream outputStream) {

        RequestParams requestParams = new RequestParams(route.getHtmlFile());
        x.http().get(requestParams, new Callback.CommonCallback<byte[]>() {

            @Override
            public void onSuccess(byte[] result) {
                if (writeOutputStream(outputStream, result)) {
                    InternalCache.getInstance().saveCache(route, result);
                }
            }

            @Override
            public void onError(Throwable ex, boolean isOnCallback) {
                byte[] result = wrapperErrorThrowable(ex);
                writeOutputStream(outputStream, result);
            }

            @Override
            public void onCancelled(CancelledException cex) {

            }

            @Override
            public void onFinished() {

            }
        });

    }

    private static boolean writeOutputStream(PipedOutputStream outputStream, byte[] result) {
        try {
            outputStream.write(result);
            outputStream.flush();
            outputStream.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static String uriForUrl(String url) {
        String uri;
        //HTTP
        String remoteFolderUrl = RouteManager.getInstance().remoteFolderUrl + "/";
        uri = deleteString(remoteFolderUrl, url);
        if (uri != null) {
            return uri;
        }
        //cache
        String cachePath = "file://" + InternalCache.getInstance().cachePath() + Constants.DEFAULT_ASSET_FILE_PATH + "/";
        uri = deleteString(cachePath, url);
        if (uri != null) {
            return uri;
        }
        //resource
        String assetsPath = AssetCache.getInstance().assetsPath() + Constants.DEFAULT_ASSET_FILE_PATH + "/";
        uri = deleteString(assetsPath, url);
        if (uri != null) {
            return uri;
        }
        return null;
    }

    private static String deleteString(String deleteString, String fromString) {
        if (fromString.startsWith(deleteString)) {
            return fromString.replace(deleteString, "");
        }
        return null;
    }

    /**
     * 是否是html文档
     *
     * @param requestUrl
     * @return
     */
    public static boolean isHtmlResource(String requestUrl) {
        if (TextUtils.isEmpty(requestUrl)) {
            return false;
        }
        String fileExtension = MimeTypeMap.getFileExtensionFromUrl(requestUrl);
        return TextUtils.equals(fileExtension, Constants.EXTENSION_HTML)
                || TextUtils.equals(fileExtension, Constants.EXTENSION_HTM);
    }

    /**
     * 是否是js文档
     *
     * @param requestUrl
     * @return
     */
    public static boolean isJsResource(String requestUrl) {
        if (TextUtils.isEmpty(requestUrl)) {
            return false;
        }
        String fileExtension = MimeTypeMap.getFileExtensionFromUrl(requestUrl);
        return TextUtils.equals(fileExtension, Constants.EXTENSION_JS);
    }

    private static byte[] wrapperErrorThrowable(Throwable ex) {
        if (ex == null) {
            return new byte[0];
        }
        return ex.toString().getBytes();
    }

//    private static boolean responseGzip(Map<String, String> headers) {
//        for (Map.Entry<String, String> entry : headers.entrySet()) {
//            if (entry.getKey()
//                    .toLowerCase()
//                    .equals(Constants.HEADER_CONTENT_ENCODING.toLowerCase())
//                    && entry.getValue()
//                    .toLowerCase()
//                    .equals(Constants.ENCODING_GZIP.toLowerCase())) {
//                return true;
//            }
//        }
//        return false;
//    }

//    private static byte[] parseGzipResponseBody(ResponseBody body) throws IOException {
//        Buffer buffer = new Buffer();
//        GzipSource gzipSource = new GzipSource(body.source());
//        while (gzipSource.read(buffer, Integer.MAX_VALUE) != -1) {
//        }
//        gzipSource.close();
//        return buffer.readByteArray();
//    }

//    private static byte[] wrapperErrorResponse(Exception exception) {
//        if (null == exception) {
//            return new byte[0];
//        }
//
//        try {
//            // generate json response
//            JSONObject result = new JSONObject();
//            result.put(Constants.KEY_NETWORK_ERROR, true);
//            return (Constants.ERROR_PREFIX + result.toString()).getBytes();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return new byte[0];
//    }

//    private static byte[] wrapperErrorResponse(Response response) {
//        if (null == response) {
//            return new byte[0];
//        }
//        try {
//            // read response content
//            Map<String, String> responseHeaders = new HashMap<>();
//            for (String field : response.headers()
//                    .names()) {
//                responseHeaders.put(field, response.headers()
//                        .get(field));
//            }
//            byte[] responseContents = new byte[0];
//            if (null != response.body()) {
//                if (responseGzip(responseHeaders)) {
//                    responseContents = parseGzipResponseBody(response.body());
//                } else {
//                    responseContents = response.body().bytes();
//                }
//            }
//
//            // generate json response
//            JSONObject result = new JSONObject();
//            result.put(Constants.KEY_RESPONSE_CODE, response.code());
//            String apiError = new String(responseContents, "utf-8");
//            try {
//                JSONObject content = new JSONObject(apiError);
//                result.put(Constants.KEY_RESPONSE_ERROR, content);
//            } catch (Exception e) {
//                e.printStackTrace();
//                result.put(Constants.KEY_RESPONSE_ERROR, apiError);
//            }
//            return (Constants.ERROR_PREFIX + result.toString()).getBytes();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return new byte[0];
//    }


}
