package com.smartattendance.config;

import com.smartattendance.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Configuration
public class SecurityConfig {
  @Bean SecurityFilterChain security(HttpSecurity http, TokenService tokens, AdminAuthService accounts) throws Exception {
    http.csrf(c->c.disable()).cors(c->{}).httpBasic(c->c.disable()).formLogin(c->c.disable())
       .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
       .authorizeHttpRequests(a->a
           .requestMatchers("/actuator/health","/api/public/**","/api/admin/auth/login").permitAll()
           .requestMatchers("/api/admin/**").authenticated()
           .requestMatchers("/api/**").denyAll()
           .anyRequest().permitAll())
       .exceptionHandling(e->e.authenticationEntryPoint((req,res,err)->{
         res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"ok\":false,\"error\":\"unauthorized\"}");
       }))
       .addFilterBefore(new OncePerRequestFilter() {
          @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException,IOException {
            String auth=req.getHeader("Authorization");
            if(auth!=null && auth.startsWith("Bearer ")){
              String user=tokens.verify(auth.substring(7));
              if(user!=null && accounts.exists(user)) SecurityContextHolder.getContext().setAuthentication(
                  new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            }
            chain.doFilter(req,res);
          }
       },UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
  @Bean CorsConfigurationSource cors(@Value("${app.frontend-origins:}") String origins) {
    CorsConfiguration cfg=new CorsConfiguration();
    if(origins!=null && !origins.isBlank()){
      Arrays.stream(origins.split(",")).map(String::trim).filter(s->!s.isBlank()).forEach(cfg::addAllowedOrigin);
    }
    cfg.setAllowedMethods(List.of("GET","POST","PUT","OPTIONS"));
    cfg.setAllowedHeaders(List.of("Content-Type","Authorization"));
    cfg.setAllowCredentials(false);
    cfg.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource src=new UrlBasedCorsConfigurationSource();src.registerCorsConfiguration("/**",cfg);return src;
  }
}
