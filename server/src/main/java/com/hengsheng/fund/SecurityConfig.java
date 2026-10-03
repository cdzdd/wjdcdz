package com.hengsheng.fund;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() {return new BCryptPasswordEncoder();}
    @Bean UserDetailsService userDetailsService(Db db) {return username->{var u=db.one("SELECT * FROM users WHERE username=?",username);return User.withUsername(username).password(u.get("passwordHash").toString()).roles(u.get("role").toString()).disabled(!Db.yes(u.get("enabled"))).build();};}
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a->a.requestMatchers("/api/auth/csrf","/api/auth/login","/api/auth/register","/actuator/health").permitAll().requestMatchers("/api/admin/**").hasAnyRole("ADMIN","OPERATOR").anyRequest().authenticated())
            .csrf(c->c.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .logout(l->l.disable()).formLogin(f->f.disable()).httpBasic(b->b.disable())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->{res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\",\"requestId\":\""+java.util.UUID.randomUUID()+"\"}");}).accessDeniedHandler((req,res,ex)->{res.setStatus(403);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"权限不足或CSRF校验失败\",\"requestId\":\""+java.util.UUID.randomUUID()+"\"}");}));
        return http.build();
    }
}
