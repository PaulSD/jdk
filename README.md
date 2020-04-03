Test case demonstrating a garbage collection race condition in HttpURLConnection/HttpsURLConnection.

See https://bugs.java.com/bugdatabase/view_bug.do?bug_id=9063818 or https://bugs.openjdk.java.net/browse/JDK-8240275 for more details.

Proposed fix is the first commit here: https://github.com/PaulSD/jdk/commits/master
(Unless the commit has been updated: https://github.com/PaulSD/jdk/commit/f045e32b6e9434b2e195d633025f435ce4696b9a )
