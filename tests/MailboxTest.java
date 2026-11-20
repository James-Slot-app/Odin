package io.hiddemoens.pluskey;
import java.io.File;
import java.nio.file.Files;
public final class MailboxTest {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("pluslens-test-").toFile();
        Mailbox box = new Mailbox(dir);
        CoreTest.check(box.clickInterval() == 200, "click interval defaults to 200 ms");
        CoreTest.check(box.activeClickInterval() == 0, "unassigned multi-press has no delay");
        box.select(KeyPress.DOUBLE, "action:torch");
        CoreTest.check(box.activeClickInterval() == 200, "configured double press uses default");
        box.setClickInterval(500);
        CoreTest.check(new Mailbox(dir).activeClickInterval() == 500, "maximum interval persists");
        box.setClickInterval(100);
        CoreTest.check(new Mailbox(dir).clickInterval() == 100, "minimum interval persists");
        for (int invalid : new int[] {99, 501}) {
            try {
                box.setClickInterval(invalid);
                throw new AssertionError("accepted an out-of-range interval");
            } catch (IllegalArgumentException expected) { }
        }
        CoreTest.check(box.clickInterval() == 100, "out-of-range interval leaves saved value intact");
        CoreTest.check(box.readStatus(1000) == null, "missing helper is unavailable");
        box.publish("Listening|1|Google Lens opened", 1000);
        CoreTest.check("Listening|1|Google Lens opened".equals(box.readStatus(2000)), "fresh heartbeat reports running");
        CoreTest.check(box.readStatus(8001) == null, "dead helper heartbeat expires");
        CoreTest.check(box.readStatus(999) == null, "future heartbeat rejected");
        CoreTest.check(!box.consumeStatusRequest(), "reading status never wakes helper");
        box.requestStatus();
        box.requestStatus();
        CoreTest.check(box.consumeStatusRequest(), "explicit liveness check reaches helper");
        CoreTest.check(!box.consumeStatusRequest(), "concurrent status requests coalesce");
        box.requestStop();
        CoreTest.check(box.stopRequested(), "app stop request reaches helper");
        box.clearStop();
        CoreTest.check(!box.stopRequested(), "restarted helper clears old stop request");
        box.requestPreview(KeyPress.SHORT);CoreTest.check(box.consumePreview()==1,"short action preview reaches helper");
        CoreTest.check(box.consumePreview()==0,"preview not repeated");
        box.requestPreview(KeyPress.LONG);CoreTest.check(box.consumePreview()==2,"long action preview reaches helper");
        box.requestPreview(KeyPress.SHORT);
        box.requestPreview(KeyPress.LONG);
        CoreTest.check(box.consumePreview() == 2, "new preview atomically replaces an unread request");
        CoreTest.check(box.consumePreview() == 0, "claimed preview is consumed once");
        box.requestPreview(KeyPress.DOUBLE);
        CoreTest.check(box.consumePreview() == KeyPress.DOUBLE, "double action preview reaches helper");
        box.requestPreview(KeyPress.TRIPLE);
        CoreTest.check(box.consumePreview() == KeyPress.TRIPLE, "triple action preview reaches helper");
        for (File file : dir.listFiles()) file.delete();
        dir.delete();
        System.out.println("Mailbox lifecycle tests passed");
    }
}
