package com.m.cenarius.resourceproxy.cache;
import android.content.res.AssetManager;
import android.text.TextUtils;
import com.m.cenarius.Constants;
import com.m.cenarius.route.Route;
import com.m.cenarius.route.RouteManager;
import com.m.cenarius.utils.AppContext;
import com.m.cenarius.utils.LogUtils;
import java.io.IOException;
import java.io.InputStream;

/**
 * 预置到asset中的只读cache
 */
public class AssetCache implements ICache {

    public static final String TAG = "AssetCache";

    public static AssetCache getInstance(String filePath) {
        return new AssetCache(filePath);
    }

    public static AssetCache getInstance() {
        return new AssetCache(null);
    }

    public String mFilePath;

    private AssetCache(String filePath) {
        mFilePath = filePath;
        if (TextUtils.isEmpty(mFilePath)) {
            mFilePath = Constants.DEFAULT_ASSET_FILE_PATH;
        }
    }

    @Override
    public CacheEntry findCache(Route route) {
        if (route == null) {
            return null;
        }
        //读取资源文件夹routes
        try {
            if (RouteManager.getInstance().resourceRoutes != null) {
                for (Route presetRoute : RouteManager.getInstance().resourceRoutes) {
                    if (presetRoute.equals(route)) {
                        //资源文件路径
                        String pathString = filePath(presetRoute.uri);
                        AssetManager assetManager = AppContext.getInstance().getResources().getAssets();
                        try {
                            InputStream inputStream = assetManager.open(pathString);
                            CacheEntry cacheEntry = new CacheEntry(0, inputStream);
                            LogUtils.i(TAG, "hit");
                            return cacheEntry;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.i(TAG, e.getMessage());
        }
        return null;
    }

    public CacheEntry findWhiteListCache(String uri){
        //资源文件路径
        String pathString = filePath(uri);
        AssetManager assetManager = AppContext.getInstance().getResources().getAssets();
        try {
            InputStream inputStream = assetManager.open(pathString);
            CacheEntry cacheEntry = new CacheEntry(0, inputStream);
            return cacheEntry;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }


    private String filePath(String uri) {
        return mFilePath + "/" + uri;
    }

    /**
     * 单个存储文件路径
     */
    public String fileUrl(Route route) {
        return assetsPath() + filePath(route.uri);
    }

    public String fileUrl(String uri) {
        return assetsPath() + filePath(uri);
    }

    /**
     * 获取assets目录
     */
    public String assetsPath() {
        return "file:///android_asset/";
    }

    @Override
    public boolean removeCache(Route route) {
        // do nothing
        return true;
    }

}
