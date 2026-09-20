package com.DOCKin.global.security.jwt;

import com.DOCKin.member.dto.CustomUserInfoDto;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.ZonedDateTime;
import java.util.Date;

@Slf4j
@Component
public class JwtUtil {
    private final Key key;
    /**
     * 파서는 한 번만 만든다. {@code Jwts.parserBuilder().build()}는 JJWT가 {@code ServiceLoader}로 역직렬화
     * 구현을 찾는 일이고, 그건 classpath의 {@code META-INF/services}를 읽는 일이라 fat jar에서는 중첩 jar를 열고
     * 닫으며 JDK {@code ZipFile} 락을 잡는다. 요청마다 두 번(검증 + subject) 하니 스레드가 몰리면 그 락 대기가
     * 응답 시간이 됐다 — 밤 20, 1,000 req/s에서 Tomcat 스레드 94개가 {@code ZipFile$Source}에 BLOCKED (#162).
     * {@link JwtParser}는 스레드 안전하다.
     */
    private final JwtParser parser;
    private final long accessTokenExpTime;
    private final long refreshTokenExpTime;

    public JwtUtil(
            @Value("${jwt.secret}") final String secretKey,
            @Value("${jwt.expiration_time}") final long accessTokenExpTime,
            @Value("${jwt.refresh_expiration_time}") final long refreshTokenExpTime)
    {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.parser = Jwts.parserBuilder().setSigningKey(key).build();
        this.accessTokenExpTime = accessTokenExpTime;
        this.refreshTokenExpTime = refreshTokenExpTime;
    }

    // Access Token 생성
    public String createAccessToken(CustomUserInfoDto member){
        return createToken(member, accessTokenExpTime);
    }

    public String createRefreshToken(CustomUserInfoDto member) {
        return createToken(member, refreshTokenExpTime);
    }

    // 빌더 패턴으로 데이터를 직접 주입하여 누락 방지
    private String createToken(CustomUserInfoDto member, long expireTime){
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime tokenValidity = now.plusSeconds(expireTime);

        // 빌드 시점에 로그를 찍어 데이터가 들어오는지 확인
        log.info("@@@ Generating Token for User: {}", member.getUserId());

        return Jwts.builder()
                .setSubject(member.getUserId()) // 필터에서 getSubject로 꺼낼 값
                .claim("userId", member.getUserId())
                .claim("name", member.getName())
                .claim("role", member.getRole())
                .setIssuedAt(Date.from(now.toInstant()))
                .setExpiration(Date.from(tokenValidity.toInstant()))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // 문자열 userId를 반환하도록 추출 로직 변경
    public String getUserId(String token){
        return parseClaims(token).getSubject();
    }

    // JWT 검증
    public boolean isValidToken(String token){
        try{
            parser.parseClaimsJws(token);
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            log.info("Invalid JWT signature.", e);
        } catch (ExpiredJwtException e) {
            log.info("Expired JWT token.", e);
        } catch (UnsupportedJwtException e) {
            log.info("Unsupported JWT token.", e);
        } catch (IllegalArgumentException e) {
            log.info("JWT claims string is empty.", e);
        }
        return false;
    }

    public long getExpiration(String token){
        return parseClaims(token).getExpiration().getTime();
    }

    // Claims 추출
    public Claims parseClaims(String accessToken){
        try{
            return parser.parseClaimsJws(accessToken).getBody();
        } catch(ExpiredJwtException e){
            return e.getClaims();
        }
    }
}