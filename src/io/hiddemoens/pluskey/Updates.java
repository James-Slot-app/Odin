package io.hiddemoens.pluskey;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public GitHub release checks; every download and install is user initiated. */
final class Updates {
 private final MainActivity activity;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final AtomicBoolean busy=new AtomicBoolean();
 private File pending;
 Updates(MainActivity activity){this.activity=activity;}
 private android.content.SharedPreferences prefs(){return activity.getSharedPreferences("updates",0);}
 boolean automatic(){return prefs().getBoolean("automatic",true);}
 void setAutomatic(boolean enabled){prefs().edit().putBoolean("automatic",enabled).apply();}
 void check(boolean manual){
  if(manual&&restorePending()){install();return;}
  long now = System.currentTimeMillis();
  if (!manual && (!automatic() || !UpdatePolicy.checkDue(now, prefs().getLong("checked", 0)))) return;
  if(!busy.compareAndSet(false,true)){if(manual)toast("Update check already running");return;}
  prefs().edit().putLong("checked", now).apply();
  if(manual)toast("Checking for updates…");
  worker.execute(()->{
   try{
    HttpURLConnection connection=connect("https://api.github.com/repos/hiddemoens/PlusKey/releases/latest");
    String json;
    try{int status=connection.getResponseCode();if(status==404){if(manual)toast("No published release yet");return;}if(status!=200)throw new IOException("Release check unavailable");json=read(connection,1048576);}finally{connection.disconnect();}
    JSONObject release=new JSONObject(json);
    String installed=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName;
    if(release.optBoolean("draft")||release.optBoolean("prerelease")||!UpdatePolicy.newer(release.optString("tag_name"),installed)){if(manual)toast("You’re up to date");return;}
    JSONArray assets=release.getJSONArray("assets");JSONObject apk=null;
    for(int i=0;i<assets.length();i++){JSONObject asset=assets.getJSONObject(i);if(UpdatePolicy.assetUrl(asset.optString("browser_download_url"))){apk=asset;break;}}
    if(apk==null)throw new IOException("Release has no APK");
    String url=apk.getString("browser_download_url"),digest=apk.optString("digest");
    if(!digest.matches("sha256:[a-fA-F0-9]{64}"))throw new IOException("Release checksum unavailable");
    final String checksum=digest.substring(7),version=release.getString("tag_name");
    ui(()->new MaterialAlertDialogBuilder(activity).setTitle("PlusKey "+version+" available")
      .setMessage("Download update from GitHub? Android will ask you before installing.")
      .setNegativeButton("Later",null).setPositiveButton("Download",(d,w)->download(url,checksum)).show());
   }catch(Exception error){if(manual)toast("Could not check updates. Try again later.");}
   finally{busy.set(false);}
  });
 }
 private void download(String url,String checksum){
  if(!busy.compareAndSet(false,true))return;
  toast("Downloading update…");
  worker.execute(()->{
   File folder=new File(activity.getCacheDir(),"updates"),file=new File(folder,"PlusKey.apk");
   try{
    if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Storage unavailable");
    HttpURLConnection connection=connect(url);MessageDigest hash=MessageDigest.getInstance("SHA-256");
    try{
     if(connection.getResponseCode()!=200)throw new IOException("Download unavailable");
     try(InputStream input=connection.getInputStream();OutputStream output=new FileOutputStream(file)){
      byte[] buffer=new byte[32768];int count;long total=0;
      while((count=input.read(buffer))!=-1){total+=count;if(total>100*1024*1024)throw new IOException("APK too large");hash.update(buffer,0,count);output.write(buffer,0,count);}
     }
    }finally{connection.disconnect();}
    StringBuilder hex=new StringBuilder();for(byte value:hash.digest())hex.append(String.format(Locale.ROOT,"%02x",value&255));
    if(!checksum.equalsIgnoreCase(hex.toString()))throw new IOException("Checksum mismatch");
    verify(file);
    prefs().edit().putBoolean("pending_install",true).commit();
    ui(()->{pending=file;install();});
   }catch(Exception error){file.delete();toast("Update could not be verified or downloaded. Try again later.");}
   finally{busy.set(false);}
  });
 }
 private void verify(File file)throws Exception{
  PackageManager manager=activity.getPackageManager();
  PackageInfo installed=manager.getPackageInfo(activity.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
  PackageInfo candidate=manager.getPackageArchiveInfo(file.getPath(),PackageManager.GET_SIGNING_CERTIFICATES);
  if(candidate==null||!installed.packageName.equals(candidate.packageName)||candidate.getLongVersionCode()<=installed.getLongVersionCode()||candidate.signingInfo==null||installed.signingInfo==null)throw new SecurityException("Invalid update");
  Signature[] expected=installed.signingInfo.getApkContentsSigners(),actual=candidate.signingInfo.getApkContentsSigners();
  if(expected.length!=actual.length||!new HashSet<>(Arrays.asList(expected)).equals(new HashSet<>(Arrays.asList(actual))))throw new SecurityException("Different signing identity");
 }
 private void install(){
  if(pending==null)return;
  if(!activity.getPackageManager().canRequestPackageInstalls()){
   new MaterialAlertDialogBuilder(activity).setTitle("Allow app updates")
    .setMessage("On next screen, allow Plus Key to install updates. Android will still ask before each installation.")
    .setNegativeButton("Later",(d,w)->pending=null).setPositiveButton("Open settings",(d,w)->{
     try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}catch(Exception error){pending=null;toast("Open app’s install permission in phone Settings");}
    }).show();return;
  }
  try{
   verify(pending);Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".updates",pending);
   Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
   activity.startActivity(intent);pending=null;prefs().edit().putBoolean("pending_install",false).apply();
  }catch(Exception error){pending=null;toast("Could not open Android installer");}
 }
 private boolean restorePending(){
  if(pending!=null)return true;
  if(!prefs().getBoolean("pending_install",false))return false;
  File file=new File(activity.getCacheDir(),"updates/PlusKey.apk");
  try{verify(file);pending=file;return true;}catch(Exception error){prefs().edit().putBoolean("pending_install",false).apply();file.delete();return false;}
 }
 void resumed(){if(restorePending()&&activity.getPackageManager().canRequestPackageInstalls())install();}
 void close(){worker.shutdown();}
 private static HttpURLConnection connect(String url)throws Exception{
  if(!url.startsWith("https://"))throw new SecurityException("HTTPS required");
  HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestProperty("User-Agent","PlusKey-Android");connection.setRequestProperty("Accept","application/vnd.github+json");return connection;
 }
 private static String read(HttpURLConnection connection,int limit)throws Exception{
  try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
   byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>limit)throw new IOException("Response too large");output.write(buffer,0,count);}return output.toString("UTF-8");
  }
 }
 private void ui(Runnable action){activity.runOnUiThread(()->{if(!activity.isFinishing()&&!activity.isDestroyed())action.run();});}
 private void toast(String message){ui(()->Toast.makeText(activity,message,Toast.LENGTH_LONG).show());}
}
