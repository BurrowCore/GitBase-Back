package org.os.gitbase.security;

import jakarta.servlet.http.HttpServletResponse;
import org.os.gitbase.constant.Constant;
import org.os.gitbase.git.config.GitTokenAuthenticationProvider;
import org.os.gitbase.git.service.CommandGitService;
import org.os.gitbase.google.OAuth2UserService;
import org.os.gitbase.security.config.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.os.gitbase.auth.service.UserDetailService;



import java.util.List;

@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Configuration
public class ApiSecurity {
    private final JwtAuthenticationFilter jwtFilter;
    private final UserDetailService userDetailsService;
    private final AuthenticationEntry authenticationEntry;

    private final String[] matchers = {
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refreshToken",
            "/api/v1/auth/logout",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/api/v1/auth/oauth2/google/url",
            "/oauth2/**",
            "/login/oauth2/code/**",
            "/api/v1/web/gitbase/create",
            "/api/v1/web/gitbase/tokens"
    };

    @Autowired
    private OAuth2UserService customOAuth2UserService;

    @Autowired
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @Autowired
    private OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;


    @Autowired
    public ApiSecurity(JwtAuthenticationFilter jwtFilter,
                       UserDetailService userDetailsService, AuthenticationEntry authenticationEntry) {
        this.jwtFilter = jwtFilter;
        this.userDetailsService = userDetailsService;
        this.authenticationEntry = authenticationEntry;

    }
    @Bean
    @Order(1)
    public SecurityFilterChain gitSecurity(HttpSecurity http,
                                           GitTokenAuthenticationProvider gitTokenAuthenticationProvider) throws Exception {
        http
                .securityMatcher(request -> request.getRequestURI().startsWith("/gitbase/"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(gitTokenAuthenticationProvider)
                .httpBasic(httpBasic -> httpBasic.authenticationEntryPoint((request, response, authException) -> {
                    response.setHeader("WWW-Authenticate", "Basic realm=\"Git Repository\"");
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, authException.getMessage());
                }))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());

        return http.build();
    }


    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http, PasswordEncoder passwordEncoder,
                                                   SessionRevokingLogoutHandler sessionRevokingLogoutHandler) throws Exception {
        return http.cors(Customizer.withDefaults())
                .csrf(csrf -> {
                    CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
                    // Read the token back from a header named XSRF-TOKEN — the same name as the
                    // cookie. Spring's default is X-XSRF-TOKEN, which is NOT in the CORS
                    // allowedHeaders list in WebConfig, so a browser preflight would reject it and
                    // every state-changing request would fail CSRF validation with a bare 403.
                    csrfTokenRepository.setHeaderName(Constant.XSRF_TOKEN);
                    csrf
                            .csrfTokenRepository(csrfTokenRepository)
                            .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                            .ignoringRequestMatchers(matchers);
                }).addFilterBefore(new org.os.gitbase.security.config.CsrfFilter(), CsrfFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authenticationProvider(authenticationProvider(passwordEncoder))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntry))
                // No Bearer-token resource server: access tokens are only accepted as the cookie, by
                // JwtAuthenticationFilter, which also checks that their sign-in session is still active.
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authorization -> authorization
                                .baseUri("/oauth2/authorize")
                                .authorizationRequestRepository(cookieAuthorizationRequestRepository())
                        )
                        .redirectionEndpoint(redirection -> redirection
                                .baseUri("/login/oauth2/code/*"))
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService))
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler))
                .authorizeHttpRequests(authorize -> authorize.requestMatchers("/api/v1/auth/register")
                        .permitAll())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/refreshToken").permitAll())
                .authorizeHttpRequests(authorize -> authorize.requestMatchers("/api/v1/auth/login")
                        .permitAll())
                .authorizeHttpRequests(authorize -> authorize.requestMatchers("/api/v1/auth/public-key")
                        .permitAll())
                .authorizeHttpRequests(
                        authorize -> authorize.requestMatchers("/actuator/health").permitAll())
                .authorizeHttpRequests(authorize -> authorize.requestMatchers("/actuator/health/**")
                        .permitAll())
                .authorizeHttpRequests(
                        authorize -> authorize.requestMatchers("/actuator/info").permitAll())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/oauth2/google/url").permitAll())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/oauth2/test").permitAll())
                .authorizeHttpRequests(
                        authorize -> authorize
                                .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                        "/v3/api-docs/**")
                                .permitAll())
                .authorizeHttpRequests(authorize -> authorize.requestMatchers("/oauth2/**").permitAll())
                .authorizeHttpRequests(authorize -> authorize.requestMatchers("/login/oauth2/code/**").permitAll())
                .authorizeHttpRequests(authorize -> authorize.requestMatchers("/login/oauth2/code/google").permitAll())
                .authorizeHttpRequests(authorize -> authorize.requestMatchers("/oauth2/authorize/google").permitAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .permitAll()
                        .logoutSuccessHandler((req, res, auth) -> {
                            String origin = req.getHeader("Origin");
                            if ("http://localhost:5173".equals(origin)
                                    || "http://localhost:8880".equals(origin)) {
                                res.setHeader("Access-Control-Allow-Origin", origin);
                            }
                            res.setHeader("Access-Control-Allow-Credentials", "true");
                            res.setStatus(HttpServletResponse.SC_OK);
                        })
                        // Revokes the sign-in session server-side (so copies of the tokens stop
                        // working) and clears the auth cookies on their own paths.
                        .addLogoutHandler(sessionRevokingLogoutHandler)
                        .invalidateHttpSession(true)
                        .deleteCookies(Constant.XSRF_TOKEN)
                        .clearAuthentication(true))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider(userDetailsService);
        authenticationProvider.setPasswordEncoder(passwordEncoder);
        return authenticationProvider;
    }

    /*
    public AuthenticationManager authManager(CommandGitService tokenService) {
        return authentication -> {
            String username = authentication.getName();
            String rawToken = authentication.getCredentials().toString();
            System.out.println("username: " + username);
            if (tokenService.validate(username, rawToken)) {
                return new UsernamePasswordAuthenticationToken(username, rawToken, List.of(() -> "GIT_USER"));
            }
            throw new BadCredentialsException("Invalid Git token");
        };
    }
    */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }


    @Bean
    public HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository() {
        return new HttpCookieOAuth2AuthorizationRequestRepository();
    }





}
