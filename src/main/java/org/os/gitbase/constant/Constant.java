package org.os.gitbase.constant;

public class Constant {
    //TOKENS
    public static final String ACCESS_TOKEN = "GITBASE-ACCESS-TOKEN";
    public static final String REFRESH_TOKEN = "GITBASE-REFRESH-TOKEN";
    public static final String XSRF_TOKEN = "XSRF-TOKEN";
    //AUTH ENDPOINT
    public static final String AUTH_MAPPING_REQUEST = "/api/v1/auth";
    public static final String REFRESH_TOKEN_MAPPING_REQUEST = "/refreshToken";
    public static final String REGISTER_MAPPING_REQUEST = "/register";
    public static final String LOGIN_MAPPING_REQUEST = "/login";
    public static final String PROFILE_INFO_MAPPING_REQUEST = "/profile-info";
    public static final String LOGOUT_MAPPING_REQUEST = "/logout";
    //GOOGLE OAUTH2 URLS
    public static final String OAUTH2_GOOGLE_URL_REQUEST = "/oauth2/google/url";
    public static final String GOOGLE_REQUEST = "/google";


    // GIT URLS
    public static final String GITBASE_MAPPING_REQUEST = "/api/v1/web/gitbase";
    public static final String CREATE_REPOSITORY = "/create";
    public static final String REPOSITORY_INFO = "/repo-info";
    public static final String REPOSITORIES = "/repositories";

    // ACTIVITIES
    public static final String ACTIVITIES_MAPPING_REQUEST = "/api/v1/activities";
    public static final String RECENT_ACTIVITIES = "/recent";
}
