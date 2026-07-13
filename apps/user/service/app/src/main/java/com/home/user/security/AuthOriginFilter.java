package com.home.user.security;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.home.user.web.UserProblemDetails;
@Component
public class AuthOriginFilter extends OncePerRequestFilter{
 private final String allowed;public AuthOriginFilter(@Value("${home.auth.allowed-origin}")String allowed){this.allowed=allowed;}
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return !"POST".equals(r.getMethod())||!("/auth/access".equals(r.getRequestURI())||"/auth/logout".equals(r.getRequestURI()));}
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws ServletException,IOException{if(!allowed.equals(r.getHeader("Origin"))){UserProblemDetails.write(s,org.springframework.http.HttpStatus.FORBIDDEN,"요청 출처가 허용되지 않았습니다","The request Origin is not allowed.","AUTH_ORIGIN_REJECTED","AuthOriginException");return;}c.doFilter(r,s);}
}
