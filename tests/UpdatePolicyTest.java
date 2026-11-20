package io.hiddemoens.pluskey;
public final class UpdatePolicyTest {
 public static void main(String[] args){
  CoreTest.check(UpdatePolicy.checkDue(1000, 0), "first launch may check");
  CoreTest.check(!UpdatePolicy.checkDue(2000, 1000), "recent attempt throttles resume even after failure");
  CoreTest.check(UpdatePolicy.checkDue(86_401_000, 1000), "next day may check");
  CoreTest.check(UpdatePolicy.checkDue(500, 1000), "clock rollback recovers");
  CoreTest.check(UpdatePolicy.newer("v1.4","1.3"),"new release");
  CoreTest.check(UpdatePolicy.newer("v1.3.1","1.3"),"patch release");
  CoreTest.check(!UpdatePolicy.newer("1.3.0","1.3"),"equal release");
  CoreTest.check(!UpdatePolicy.newer("1.2","1.3"),"no downgrade");
  CoreTest.check(!UpdatePolicy.newer("v1.4-beta","1.3"),"no prerelease tags");
  CoreTest.check(UpdatePolicy.assetUrl("https://github.com/hiddemoens/PlusKey/releases/download/v1.4/PlusKey-1.4.apk"),"own asset");
  CoreTest.check(!UpdatePolicy.assetUrl("https://github.com/other/PlusKey/releases/download/v1.4/a.apk"),"reject other repo");
  CoreTest.check(!UpdatePolicy.assetUrl("http://github.com/hiddemoens/PlusKey/releases/download/v1.4/a.apk"),"require https");
  System.out.println("Update policy tests passed");
 }
}
