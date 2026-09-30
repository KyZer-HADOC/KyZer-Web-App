package com.kyzerhadoc.app;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

  private static final String PAUSE_MEDIA_JS =
    "document.querySelectorAll('audio,video').forEach(function(m){"
    + "m.dataset.kzWasPlaying = m.paused ? '' : '1'; m.pause();});";

  private static final String RESUME_MEDIA_JS =
    "document.querySelectorAll('audio,video').forEach(function(m){"
    + "if(m.dataset.kzWasPlaying==='1'){var p=m.play(); if(p&&p.catch){p.catch(function(){});}}});";

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    WebView webView = getBridge().getWebView();

    // Let the background song autoplay without a tap
    webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

    // Native downloads: notification + progress, saved to Downloads
    webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
      try {
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        if (mimetype != null) req.setMimeType(mimetype);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) req.addRequestHeader("Cookie", cookies);
        if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
        req.setTitle(fileName);
        req.setDescription("KyZer_HADOC Download");
        req.allowScanningByMediaScanner();
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        dm.enqueue(req);
        Toast.makeText(this, "Downloading: " + fileName, Toast.LENGTH_LONG).show();
      } catch (Exception e) {
        Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
      }
    });
  }

  @Override
  public void onPause() {
    super.onPause();
    // Stop the background song when the app is minimised
    getBridge().getWebView().evaluateJavascript(PAUSE_MEDIA_JS, null);
  }

  @Override
  public void onResume() {
    super.onResume();
    getBridge().getWebView().evaluateJavascript(RESUME_MEDIA_JS, null);
  }
}
