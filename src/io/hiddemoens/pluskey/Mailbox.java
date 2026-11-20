package io.hiddemoens.pluskey;
import java.io.*;
import java.util.*;
final class Mailbox {
    static final String STOP_REQUEST = "helper-stop";
    static final String PREVIEW_REQUEST = "preview-request";
    static final String STATUS_REQUEST = "status-request";
    static final String STATUS_FILE = "helper-status";
    static final String SESSION_FILE = "helper-session";
    static final int MIN_CLICK_INTERVAL_MS = 100;
    static final int MAX_CLICK_INTERVAL_MS = 500;
    static final int DEFAULT_CLICK_INTERVAL_MS = 200;
    private static final String CLICK_INTERVAL_FILE = "click-interval";
    private static final java.util.regex.Pattern PATTERN_FILE = java.util.regex.Pattern.compile("gesture-[.-]{1,8}");
    private static final long STATUS_MAX_AGE_MS = 7_000;
    private final File dir;
    private static String targetFile(int gesture) {
        switch (gesture) {
            case KeyPress.SHORT: return "short-target";
            case KeyPress.LONG: return "long-target";
            case KeyPress.DOUBLE: return "double-target";
            case KeyPress.TRIPLE: return "triple-target";
            default: return "pattern-target-" + GesturePatterns.text(gesture);
        }
    }

    void select(int gesture, String target) throws IOException {
        String name = targetFile(gesture);
        if (gesture > KeyPress.TRIPLE && !marker(gesture).exists()) throw new IllegalArgumentException("Pattern was removed");
        if (target != null && target.startsWith("hold:")) {
            String prefix = GesturePatterns.text(gesture);
            if (!prefix.endsWith("-")) throw new IllegalArgumentException("Hold needs a final long press");
            for (int other : patterns()) if (other != gesture && GesturePatterns.text(other).startsWith(prefix))
                throw new IllegalArgumentException("Remove longer patterns starting with this sequence first");
        }
        if (gesture != KeyPress.SHORT && target == null) {
            File file = new File(dir, name);
            if (file.exists() && !file.delete()) throw new IOException("Could not clear selected action");
        } else {
            writeTarget(name, target);
        }
    }

    private File marker(int gesture) { return new File(dir, "gesture-" + GesturePatterns.text(gesture)); }
    Set<Integer> patterns() {
        return gestureIds(false);
    }
    Set<Integer> activeGestures() {
        return gestureIds(true);
    }
    private Set<Integer> gestureIds(boolean assignedOnly) {
        String[] listing = dir == null ? null : dir.list();
        Set<String> names = listing == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(listing));
        Set<Integer> result = new TreeSet<>();
        for (String name : names) {
            if (!name.startsWith("gesture-") || !PATTERN_FILE.matcher(name).matches()) continue;
            int id = GesturePatterns.id(name.substring(8));
            if (id > KeyPress.TRIPLE && (!assignedOnly || names.contains(targetFile(id)))) result.add(id);
        }
        if (assignedOnly) {
            result.add(KeyPress.SHORT);
            for (int id = KeyPress.LONG; id <= KeyPress.TRIPLE; id++)
                if (names.contains(targetFile(id))) result.add(id);
        }
        return result;
    }
    void addPattern(int id) throws IOException {
        if (id <= KeyPress.TRIPLE || marker(id).exists()) throw new IllegalArgumentException("This pattern already has a tile");
        String pattern = GesturePatterns.text(id);
        for (int other : activeGestures()) {
            String prefix = GesturePatterns.text(other);
            if (pattern.startsWith(prefix)) {
                String target = selected(other);
                if (target != null && target.startsWith("hold:")) throw new IllegalArgumentException("Starts with an existing While held pattern");
            }
        }
        if (!marker(id).createNewFile()) throw new IOException("Could not save pattern");
    }
    void removePattern(int id) throws IOException {
        if (id <= KeyPress.TRIPLE) throw new IllegalArgumentException("Built-in gesture");
        select(id, null);
        if (!marker(id).delete()) throw new IOException("Could not remove pattern");
    }

    boolean multiPressEnabled() {
        return new File(dir, targetFile(KeyPress.DOUBLE)).exists()
            || new File(dir, targetFile(KeyPress.TRIPLE)).exists();
    }

    int activeClickInterval() {
        return multiPressEnabled() ? clickInterval() : 0;
    }

    int clickInterval() {
        if (dir != null) {
            try (DataInputStream input = new DataInputStream(new FileInputStream(new File(dir, CLICK_INTERVAL_FILE)))) {
                int interval = input.readUnsignedShort();
                if (validClickInterval(interval)) return interval;
            } catch (IOException ignored) { }
        }
        return DEFAULT_CLICK_INTERVAL_MS;
    }

    void setClickInterval(int interval) throws IOException {
        if (!validClickInterval(interval)) throw new IllegalArgumentException("Invalid click interval");
        if (dir == null) throw new IOException("App storage unavailable");
        File next = new File(dir, CLICK_INTERVAL_FILE + ".next");
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(next))) {
            output.writeShort(interval);
        }
        if (!next.renameTo(new File(dir, CLICK_INTERVAL_FILE))) throw new IOException("Could not save click interval");
    }

    private static boolean validClickInterval(int interval) {
        return interval >= MIN_CLICK_INTERVAL_MS && interval <= MAX_CLICK_INTERVAL_MS;
    }

    private void writeTarget(String name,String target)throws IOException {
        if (!Options.validTarget(target)) throw new IllegalArgumentException("Choose an app or action");
        File next = new File(dir, name+".next");
        try (FileOutputStream output = new FileOutputStream(next)) { output.write(target.getBytes("UTF-8")); }
        if (!next.renameTo(new File(dir, name))) throw new IOException("Could not save selected app");
    }
    String selected(int gesture) throws IOException {
        return readTarget(targetFile(gesture));
    }
    private String readTarget(String name)throws IOException {
        File file = new File(dir, name);
        try (FileInputStream input = new FileInputStream(file)) {
            long size = input.getChannel().size();
            if (size > 32768) throw new IOException("Invalid selection; choose action again");
            byte[] bytes = new byte[(int)size];
            new DataInputStream(input).readFully(bytes);
            String target = new String(bytes, "UTF-8");
            if (!Options.validTarget(target)) throw new IOException("Invalid selection; choose action again");
            return target;
        } catch (FileNotFoundException error) {
            if (!file.exists()) return null;
            throw error;
        }
    }
    Mailbox(File dir) { this.dir = dir; }
    void publishVersion(String version)throws IOException {try(FileOutputStream output=new FileOutputStream(new File(dir,"helper-version"))){output.write(version.getBytes("UTF-8"));}}
    String runningVersion()throws IOException {try(BufferedReader input=new BufferedReader(new FileReader(new File(dir,"helper-version")))){return input.readLine();}catch(FileNotFoundException e){return "";}}
    String readStatus(long now) throws IOException {
        if (dir == null) return null;
        String status = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(dir, STATUS_FILE)))) {
            String first = reader.readLine();
            if (first != null) {
                long age = now - Long.parseLong(first);
                if (age >= 0 && age <= STATUS_MAX_AGE_MS) status = reader.readLine();
            }
        } catch (FileNotFoundException | NumberFormatException ignored) { }
        return status;
    }
    void requestStatus() throws IOException {
        if (dir == null) throw new IOException("App storage unavailable");
        // Empty marker. Creation is atomic and repeated requests need only one reply.
        new File(dir, STATUS_REQUEST).createNewFile();
    }
    boolean consumeStatusRequest() {
        File request = new File(dir, STATUS_REQUEST);
        return request.exists() && request.delete();
    }
    void publish(String status, long now) throws IOException {
        if (dir == null || !dir.isDirectory()) throw new IOException("Open Plus Lens app once before starting helper");
        File next = new File(dir, STATUS_FILE + ".next");
        try (FileOutputStream output = new FileOutputStream(next)) {
            output.write((now + "\n" + status + "\n").getBytes("UTF-8"));
        }
        if (!next.renameTo(new File(dir, STATUS_FILE))) throw new IOException("Cannot publish status");
    }
    void requestStop() throws IOException {
        writeRequest(STOP_REQUEST, 1);
    }
    boolean stopRequested() { return dir != null && new File(dir, STOP_REQUEST).exists(); }
    void requestPreview(int gesture) throws IOException {
        if (gesture <= KeyPress.TRIPLE) writeRequest(PREVIEW_REQUEST, gesture);
        else {
            File next = new File(dir, PREVIEW_REQUEST + ".next");
            try (DataOutputStream output = new DataOutputStream(new FileOutputStream(next))) { output.writeInt(gesture); }
            if (!next.renameTo(new File(dir, PREVIEW_REQUEST))) throw new IOException("Could not send preview");
        }
    }
    private void writeRequest(String name, int value) throws IOException {
        if (dir == null) throw new IOException("App storage unavailable");
        File next = new File(dir, name + ".next");
        try (FileOutputStream output = new FileOutputStream(next)) { output.write(value); }
        if (!next.renameTo(new File(dir, name))) throw new IOException("Could not send " + name);
    }
    int consumePreview() throws IOException {
        File file = new File(dir, PREVIEW_REQUEST);
        if (!file.exists()) return 0;
        File claimed = new File(dir, PREVIEW_REQUEST + ".claimed");
        if (!file.renameTo(claimed)) throw new IOException("Could not read preview request");
        try (FileInputStream input = new FileInputStream(claimed)) {
            if (input.getChannel().size() == 4) {
                int id = new DataInputStream(input).readInt();
                return patterns().contains(id) ? id : 0;
            }
            int value = input.read();
            return value >= KeyPress.SHORT && value <= KeyPress.TRIPLE ? value : 0;
        } finally {
            claimed.delete();
        }
    }
    void clearStop() { if (dir != null) new File(dir, STOP_REQUEST).delete(); }
}
