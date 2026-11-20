package io.hiddemoens.pluskey;

import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Only app-published, enabled static shortcuts that resolve to that app. */
final class Targets {
    static final String[] ACTIONS=Options.ACTIONS;
    static final String[] LABELS={"Toggle flashlight","Sound → Vibrate → Silent","Sound ↔ Vibrate","Sound ↔ Silent","Silent","Vibrate","Sound on","Screenshot","Lock screen","Open notifications","Open quick settings","Play / pause","Next track","Previous track","Volume up","Volume down","Start default assistant","Toggle Wi-Fi","Toggle mobile data","Switch data SIM",
        "Previous app","Pause media or toggle flashlight","Pause media","Toggle hotspot","Do Not Disturb · 1 hour","Use SIM 1 for data","Use SIM 2 for data","Record audio"};
    static final class Choice {final String label,target;Choice(String label,String target){this.label=label;this.target=target;}}
    static String label(Context context,String target){
        if(target==null)return "Managed by OnePlus";
        if(target.startsWith("hold:") && Options.canHold(target.substring(5)))return "Hold: " + label(context,target.substring(5));
        String[] fan = Options.fanApps(target);
        if (fan != null) return "App fan · " + fan.length + " apps";
        for(int i=0;i<ACTIONS.length;i++)if(ACTIONS[i].equals(target))return LABELS[i];
        String[] branches = Options.mediaBranches(target);
        if (branches != null) return "Playing: " + label(context, branches[0]) + " · Otherwise: " + label(context, branches[1]);
        String saved=context.getSharedPreferences("choices",0).getString(target,"");
        if(Options.isTile(target)) {
            ComponentName component=ComponentName.unflattenFromString(target.substring(5));
            try{return context.getPackageManager().getServiceInfo(component,0).loadLabel(context.getPackageManager()).toString();}
            catch(PackageManager.NameNotFoundException ignored){return saved.isEmpty() ? component.flattenToShortString() : saved;}
        }
        if(!saved.isEmpty())return saved;
        try{return context.getPackageManager().getActivityInfo(ComponentName.unflattenFromString(target),0).loadLabel(context.getPackageManager()).toString();}
        catch(Exception error){return "Choose an action";}
    }
    static void remember(Context context,Choice choice){context.getSharedPreferences("choices",0).edit().putString(choice.target,choice.label).apply();}
    static ArrayList<Choice> shortcuts(Context context,ResolveInfo app){
        ArrayList<Choice> found=new ArrayList<>();String owner=app.activityInfo.packageName;
        try{
            ActivityInfo info=app.activityInfo;
            int resource=info.metaData==null?0:info.metaData.getInt("android.app.shortcuts",0);
            if(resource==0)return found;
            Resources resources=context.getPackageManager().getResourcesForApplication(info.applicationInfo);
            try(XmlResourceParser parser=resources.getXml(resource)){
                String label=null;boolean enabled=false;ArrayList<Intent> intents=new ArrayList<>();
                int event;
                while((event=parser.next())!=XmlPullParser.END_DOCUMENT){
                    if(event==XmlPullParser.START_TAG && parser.getName().equals("shortcut")){
                        enabled=parser.getAttributeBooleanValue("http://schemas.android.com/apk/res/android","enabled",true);
                        int name=parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android","shortcutShortLabel",0);
                        label=name==0?parser.getAttributeValue("http://schemas.android.com/apk/res/android","shortcutShortLabel"):resources.getString(name);intents.clear();
                    }else if(event==XmlPullParser.START_TAG && parser.getName().equals("intent") && enabled){
                        Intent intent=Intent.parseIntent(resources,parser,Xml.asAttributeSet(parser));
                        intent.setPackage(owner);
                        ResolveInfo resolved=context.getPackageManager().resolveActivity(intent,0);
                        if(resolved==null||!owner.equals(resolved.activityInfo.packageName)||!resolved.activityInfo.exported||
                            (resolved.activityInfo.permission!=null && context.checkSelfPermission(resolved.activityInfo.permission)!=PackageManager.PERMISSION_GRANTED)){enabled=false;continue;}
                        intent.setComponent(new ComponentName(owner,resolved.activityInfo.name));
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        intents.add(intent);
                    }else if(event==XmlPullParser.END_TAG && parser.getName().equals("shortcut") && enabled && label!=null && !intents.isEmpty()){
                        StringBuilder encoded=new StringBuilder();
                        for(Intent intent:intents){if(encoded.length()>0)encoded.append('\0');encoded.append(intent.toUri(Intent.URI_INTENT_SCHEME));}
                        String target="shortcut:"+owner+":"+Base64.getUrlEncoder().withoutPadding().encodeToString(encoded.toString().getBytes(StandardCharsets.UTF_8));
                        if(Options.validTarget(target))found.add(new Choice(label,target));
                    }
                }
            }
        }catch(Exception ignored){}
        return found;
    }
    static List<Choice> lockedActivities(Context context, ApplicationInfo app, String appLabel, boolean hasSecureCamera) {
        List<Choice> choices = new ArrayList<>();
        String owner = app.packageName;
        PackageManager pm = context.getPackageManager();
        String android = "http://schemas.android.com/apk/res/android";
        try {
            Resources resources = pm.getResourcesForApplication(app);
            Set<String> declared = new HashSet<>();
            try (XmlResourceParser xml = resources.getAssets().openXmlResourceParser("AndroidManifest.xml")) {
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.getEventType() != XmlPullParser.START_TAG || !"activity".equals(xml.getName())) continue;
                    int ref = xml.getAttributeResourceValue(android, "showWhenLocked", 0);
                    if (!(ref == 0 ? xml.getAttributeBooleanValue(android, "showWhenLocked", false) : resources.getBoolean(ref))) continue;
                    String name = xml.getAttributeValue(android, "name");
                    if (name == null) continue;
                    if (name.startsWith(".")) name = owner + name;
                    else if (!name.contains(".")) name = owner + "." + name;
                    declared.add(name);
                }
            }
            if (declared.isEmpty()) return choices;
            ActivityInfo[] activities = pm.getPackageInfo(owner, PackageManager.GET_ACTIVITIES).activities;
            if (activities == null) return choices;
            Set<String> labels = new HashSet<>();
            for (ActivityInfo info : activities) {
                if (!declared.contains(info.targetActivity == null ? info.name : info.targetActivity)
                    || !info.exported || info.permission != null || !info.enabled) continue;
                ComponentName component = new ComponentName(owner, info.name);
                if (pm.resolveActivity(new Intent().setComponent(component), 0) == null) continue;
                String label = info.loadLabel(pm).toString().trim();
                if (!Options.offerLockedLabel(hasSecureCamera, appLabel, label, labels)) continue;
                choices.add(new Choice(label + " · Open while locked",
                    Options.LOCKED_ACTIVITY + component.flattenToString()));
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException |
                 java.io.IOException | org.xmlpull.v1.XmlPullParserException ignored) {}
        return choices;
    }
    static Intent[] intents(String target)throws Exception{
        if(!Options.validTarget(target))throw new IllegalArgumentException("Invalid action");
        if (Options.fanApps(target) != null) {
            return new Intent[]{new Intent().setClassName("io.hiddemoens.pluskey", "io.hiddemoens.pluskey.AppFanActivity")
                .putExtra("target", target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)};
        }
        if (Options.isConfigured(target)) {
            return new Intent[]{new Intent().setClassName("io.hiddemoens.pluskey", "io.hiddemoens.pluskey.ConfiguredShortcut")
                .putExtra("target", target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)};
        }
        if (Options.isLockedAppAction(target)) {
            boolean voice = target.startsWith("voice:");
            Intent intent = new Intent(voice ? android.speech.RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE : Intent.ACTION_CREATE_NOTE);
            if (voice) intent.putExtra(android.speech.RecognizerIntent.EXTRA_SECURE, true);
            return new Intent[]{intent.setPackage(target.substring(target.indexOf(':') + 1)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)};
        }
        if (Options.isLockedActivity(target)) {
            ComponentName component = ComponentName.unflattenFromString(target.substring(Options.LOCKED_ACTIVITY.length()));
            return new Intent[]{new Intent(Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)};
        }
        if (Options.isSecureCamera(target)) {
            String owner = target.substring(Options.SECURE_CAMERA.length());
            return new Intent[]{new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                .setPackage(owner).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)};
        }
        if(Options.validComponent(target))return new Intent[]{new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName.unflattenFromString(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)};
        if(!target.startsWith("shortcut:"))return new Intent[0];
        int split=target.indexOf(':',9);String owner=target.substring(9,split);
        String decoded=new String(Base64.getUrlDecoder().decode(target.substring(split+1)),StandardCharsets.UTF_8);
        ArrayList<Intent> intents=new ArrayList<>();
        for(String uri:decoded.split("\u0000",-1)){
            Intent intent=Intent.parseUri(uri,Intent.URI_INTENT_SCHEME);
            if(intent.getComponent()==null||!owner.equals(intent.getComponent().getPackageName()))throw new IllegalArgumentException("Shortcut belongs to another app");
            intent.setPackage(owner);intent.setSelector(null);intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);intents.add(intent);
        }
        return intents.toArray(new Intent[0]);
    }
}
