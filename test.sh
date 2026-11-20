#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/test
javac -d build/test src/io/hiddemoens/pluskey/InputEvents.java tests/InputEventsTest.java src/io/hiddemoens/pluskey/GesturePatterns.java src/io/hiddemoens/pluskey/LightState.java src/io/hiddemoens/pluskey/RecordingLease.java src/io/hiddemoens/pluskey/KeyPress.java src/io/hiddemoens/pluskey/Policy.java src/io/hiddemoens/pluskey/Options.java src/io/hiddemoens/pluskey/Mailbox.java src/io/hiddemoens/pluskey/AutomaticDebugging.java src/io/hiddemoens/pluskey/UpdatePolicy.java tests/PatternTest.java tests/PatternStorageTest.java tests/RecordingLeaseTest.java tests/UpdatePolicyTest.java tests/CoreTest.java tests/MailboxTest.java tests/OptionsTest.java tests/AppFanTest.java tests/AutomaticDebuggingTest.java
java -cp build/test io.hiddemoens.pluskey.InputEventsTest
java -cp build/test io.hiddemoens.pluskey.PatternTest
java -cp build/test io.hiddemoens.pluskey.PatternStorageTest
java -cp build/test io.hiddemoens.pluskey.RecordingLeaseTest
java -cp build/test io.hiddemoens.pluskey.CoreTest
java -cp build/test io.hiddemoens.pluskey.MailboxTest
java -cp build/test io.hiddemoens.pluskey.OptionsTest
java -cp build/test io.hiddemoens.pluskey.AutomaticDebuggingTest

java -cp build/test io.hiddemoens.pluskey.UpdatePolicyTest

java -cp build/test io.hiddemoens.pluskey.AppFanTest
