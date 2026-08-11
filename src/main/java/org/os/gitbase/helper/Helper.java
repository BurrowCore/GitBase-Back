package org.os.gitbase.helper;

public class Helper {
    public static String removeAtSymbolAndFollowing(String email) {
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return email;
    }
}
