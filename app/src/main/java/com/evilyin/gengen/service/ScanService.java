package com.evilyin.gengen.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.evilyin.gengen.AccessTokenKeeper;
import com.evilyin.gengen.activity.MainActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;

import cn.byr.bbs.sdk.api.ArticleApi;
import cn.byr.bbs.sdk.api.SearchApi;
import cn.byr.bbs.sdk.api.SectionApi;
import cn.byr.bbs.sdk.auth.Oauth2AccessToken;
import cn.byr.bbs.sdk.exception.BBSException;
import cn.byr.bbs.sdk.net.RequestListener;

/**
 * 搜索发帖
 *
 * @author evilyin(ChenZhixi)
 * @since 2015-6-3
 */

public class ScanService extends Service {

    private static final String doubanUrl = "http://www.douban.com/group/search?cat=1013&q=";
    private static final String baiduUrl = "http://www.baidu.com/s?wd=";
    private static final int scanTime = 600;//搜索间隔时长（秒）

    private Oauth2AccessToken mAccessToken;
    private String resultUrl = "";
    private Context mContext;
    private ConnectivityManager mConnectivityManager;

    Handler handler =new Handler();
    Runnable runnable=new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, scanTime * 1000);

            SectionApi mSectionApi = new SectionApi(mAccessToken);
            mSectionApi.getSection("4", new sectionListener());
            mSectionApi.getSection("5", new sectionListener());
            mSectionApi.getSection("6", new sectionListener());
            SearchApi searchApi = new SearchApi(mAccessToken);
            searchApi.threadByAuthor("FamilyLife", "guitarmega", new searchListener());
        }
    };

    @Override
    public void onCreate() {
        Log.i("ScanService", "启动");
        mContext = this;
        mAccessToken = AccessTokenKeeper.readAccessToken(this);

        mConnectivityManager = (ConnectivityManager) this.getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
        //判断wifi是否可用
        if (info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI) {
            handler.post(runnable);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(runnable);
        if (mConnectivityManager != null) {
            mConnectivityManager = null;
        }
        Log.i("ScanService", "搜索停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 通过搜索引擎搜索关键字
     *
     * @param keyword 关键字
     * @return 搜索排行第一的网址url，仅当关键字包含或包含于所搜结果时有效，否则为空
     */
    private String search(String keyword) {
        String result = "";
        if (keyword.length() >= 23) {
            keyword = keyword.substring(0, 22);
        }
        try {
            Document document = Jsoup.connect(doubanUrl + URLEncoder.encode(keyword, "UTF-8")).get();
            Elements links = document.select("td.td-subject > a[href]");
            Element link = links.get(0);
            String linkKeyword = link.text();
            if (keyword.contains(linkKeyword) || linkKeyword.contains(keyword)) {
                result = link.attr("href");
            } else {
                document = Jsoup.connect(baiduUrl + URLEncoder.encode(keyword, "UTF-8")).get();
                links = document.select("div.resitem > a[href]");
                link = links.get(0);
                String bdUrl = link.attr("href");
                Document document1 = Jsoup.connect(bdUrl).get();
                String bdresult=document1.location();
                if (bdresult.startsWith("http://m.baidu.com")) {
                    result = bdresult.substring(33, bdresult.indexOf("&word"));
                } else {
                    result = bdresult;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        Log.i("search", "关键字搜索结果：" + result);
        return result;
    }

    class sectionListener implements RequestListener {

        @Override
        public void onComplete(String response) {
            //获取分区信息成功，开始搜索发帖
            try {
                JSONObject sectionObject = new JSONObject(response);
                JSONArray boardArray = sectionObject.getJSONArray("board");
                SearchApi mSearchApi = new SearchApi(mAccessToken);

                for (int i = 0; i < boardArray.length(); i++) {
                    String boardName = boardArray.getJSONObject(i).getString("name");
                    mSearchApi.threadByAuthor(boardName, "guitarmega", new searchListener());//根据用户名搜索
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onException(BBSException e) {
            e.printStackTrace();
        }
    }

    class searchListener implements RequestListener {

        @Override
        public void onComplete(String response) {
            //搜索结果取最新发帖
            try {
                JSONObject searchObject = new JSONObject(response);
                //判断搜索结果是否为空
                JSONObject pagination = searchObject.getJSONObject("pagination");
                if (pagination.getInt("item_all_count") != 0) {
                    //如果不为空，取最新的一帖
                    JSONArray searchArray = searchObject.getJSONArray("threads");
                    JSONObject resultThread = searchArray.getJSONObject(0);
                    final String title = resultThread.getString("title");
                    final String board = resultThread.getString("board_name");
                    final int id = resultThread.getInt("id");
                    int now = (int) (System.currentTimeMillis() / 1000);
                    if (now - resultThread.getInt("post_time") < scanTime) {
                        //发帖时间小于搜索间隔，找到新帖
                        Log.i("ScanService", "找到新帖！标题：" + title + " 版面：" + board);
                        Thread.sleep(10000);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                resultUrl = search(title);
                                String resultContent = "";
                                if (!resultUrl.equals("")) {
                                    resultContent = "原帖地址：[url=" + resultUrl + "]" + resultUrl + "[/url]";
                                }
                                //发表回复
                                String content = "楼主sb\n" + resultContent;
                                ArticleApi mArticleApi = new ArticleApi(mAccessToken);
                                mArticleApi.reply(board, new articleListener(), "Re: " + title, content, id);
                            }
                        }).start();

                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onException(BBSException e) {
            e.printStackTrace();
        }
    }

    class articleListener implements RequestListener {

        @Override
        public void onComplete(String s) {
            try {
                JSONObject articleObject = new JSONObject(s);
                String articleBoard = articleObject.getString("board_name");
                Log.i("ScanService", "发帖成功，版面：" + articleBoard);

                //发帖结果保存至SP
                SharedPreferences preferences = mContext.getSharedPreferences("bbs_post_result", MODE_APPEND);
                SharedPreferences.Editor editor=preferences.edit();
                editor.putString("title", articleObject.getString("title"));
                editor.putString("board", articleBoard);
                editor.apply();

                //通知
                NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(NOTIFICATION_SERVICE);
                NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext);
                PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 1, new Intent(mContext, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
                builder.setContentTitle("跟跟")
                        .setContentText("sb又犯贱了")
                        .setContentIntent(pendingIntent);
                Notification notification = builder.build();
                notification.flags = Notification.FLAG_AUTO_CANCEL;
                notificationManager.notify(1, notification);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onException(BBSException e) {
            e.printStackTrace();
        }
    }
}
