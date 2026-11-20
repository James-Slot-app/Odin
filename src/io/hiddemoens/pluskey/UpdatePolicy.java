package io.hiddemoens.pluskey;
final class UpdatePolicy {
 static boolean checkDue(long now, long lastAttempt) {
  return lastAttempt == 0 || now < lastAttempt || now - lastAttempt >= java.util.concurrent.TimeUnit.DAYS.toMillis(1);
 }
 static boolean newer(String release,String current){
  if(release==null||current==null)return false;
  release=release.replaceFirst("^v","");
  if(!release.matches("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}")||!current.matches("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}"))return false;
  String[] a=release.split("\\."),b=current.split("\\.");
  for(int i=0;i<Math.max(a.length,b.length);i++){int x=i<a.length?Integer.parseInt(a[i]):0,y=i<b.length?Integer.parseInt(b[i]):0;if(x!=y)return x>y;}return false;
 }
 static boolean assetUrl(String url){return url!=null&&url.matches("https://github\\.com/hiddemoens/PlusKey/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.apk");}
}
