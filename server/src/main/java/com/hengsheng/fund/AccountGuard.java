package com.hengsheng.fund;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AccountGuard implements WebMvcConfigurer,HandlerInterceptor {
    private final Db db;
    public AccountGuard(Db db){this.db=db;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/auth/csrf","/api/auth/login","/api/auth/register");}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!auth.isAuthenticated()||"anonymousUser".equals(auth.getPrincipal()))return true;var user=db.one("SELECT enabled,must_change_password FROM users WHERE username=?",auth.getName());
        if(!Db.yes(user.get("enabled")))throw new ApiException(403,"USER_DISABLED","账户已停用");
        if(!"GET".equals(request.getMethod())&&Db.yes(user.get("mustChangePassword"))&&!Set.of("/api/account/password","/api/auth/logout").contains(request.getRequestURI()))throw new ApiException(403,"PASSWORD_CHANGE_REQUIRED","请先修改管理员重置的临时密码");return true;
    }
}
