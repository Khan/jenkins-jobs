// A new exception type, used in vars/notify.groovy.

package org.khanacademy;

// Used to set status to FAILED and emit the failure reason to slack/email.
class FailedBuild extends Exception {
};
