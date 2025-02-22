package com.shoppinglist.springboot.Token;

import com.shoppinglist.springboot.user.User;
import com.shoppinglist.springboot.exceptions.ResourceNotFoundException;
import com.shoppinglist.springboot.exceptions.NotValidResourceException;
import com.shoppinglist.springboot.user.Error;
import com.shoppinglist.springboot.user.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.Cookie;
import java.security.Key;
import java.util.Date;

@Service
public class TokenService {
    private static final long EXPIRATION_TIME_ACCESS = 900000;
    private static final long EXPIRATION_TIME_REFRESH = 3600000 * 24;
    private final TokenDAO tokenDAO;
    private final Key jwtKey;

    @Autowired
    public TokenService(TokenDAO tokenDAO, @Value("${jwt.Key}") String secretKey) {
        this.tokenDAO = tokenDAO;
        this.jwtKey = Keys.hmacShaKeyFor(secretKey.getBytes());
    }

    public void addToken(Integer userId, String tokenString) {
        if (tokenString == null) {
            throw new NotValidResourceException("Missing data");
        }
        Token token = new Token(userId, tokenString);
        tokenDAO.addToken(token);
    }

    public Token getTokenByContent(String token) {
        return tokenDAO.getTokenByContent(token)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Token [%s] not found".formatted(token))
                );
    }

    public String generateToken(long expirationDate, Integer userID) {
        long currentTimeMillis = System.currentTimeMillis();
        Date expirationDateToken = new Date(currentTimeMillis + expirationDate);
        String token = Jwts.builder()
                .setSubject(String.valueOf(userID))
                .setExpiration(expirationDateToken)
                .signWith(jwtKey, SignatureAlgorithm.HS512)
                .compact();
        return token;
    }

    public ResponseEntity < ? > loginUser(String email, String password, UserService userService) {
        try {
            User user = userService.getUserByEmail(email);
            // checking password
            if (BCrypt.checkpw(password, user.getPassword())) {
                // generating tokens
                String accessToken = generateToken(EXPIRATION_TIME_ACCESS, user.getId());
                String refreshToken = generateToken(EXPIRATION_TIME_REFRESH, user.getId());
                // adding refresh token to database
                addToken(user.getId(), refreshToken);
                // adding refresh token to cookies
                Cookie cookie = new Cookie("refreshToken", refreshToken);
                cookie.setHttpOnly(true);
                cookie.setPath("/api/auth");
                String cookieValue = String.format("%s=%s; HttpOnly; Path=/", cookie.getName(), cookie.getValue());
                return ResponseEntity.ok()
                        .header("Set-Cookie", cookieValue)
                        .body(accessToken);
            } else {
                Error error = new Error("Validation", "Password", "Invalid password");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }
        } catch (ResourceNotFoundException ex) {
            Error error = new Error("Validation", "E-mail", "Invalid e-mail");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    public ResponseEntity < ? > refreshToken(String refreshToken) {
        if (!refreshToken.isEmpty()) {
            try {
                Claims claims = Jwts.parser().setSigningKey(jwtKey).parseClaimsJws(refreshToken).getBody();
                // getting userID from refresh token
                Long userID = Long.parseLong(claims.getSubject());
                // getting refresh token from database
                Token dataBaseRefreshToken = getTokenByContent(refreshToken);
                // checking refresh token
                if (dataBaseRefreshToken.getContent().equals(refreshToken) && dataBaseRefreshToken.getUserID().equals(userID.intValue())) {
                    // Creating new access token
                    String accessToken = generateToken(EXPIRATION_TIME_ACCESS, userID.intValue());
                    return ResponseEntity.ok().body(accessToken);
                } else {
                    Error error = new Error("Refresh", null, "Invalid token");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
                }
            } catch (ExpiredJwtException e) {
                // token unnactive
                Error error = new Error("Refresh", null, "Expired token");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            } catch (ResourceNotFoundException e) {
                // token not in database
                Error error = new Error("Refresh", null, "token not in database");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            }
        } else {
            // Cookie refreshToken not found
            Error error = new Error("Refresh", null, "Empty cookie");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
    }

    public boolean validateAccessToken(String token) {
        try {
            Jwts.parser().setSigningKey(jwtKey).parseClaimsJws(token).getBody();
            return true;
        } catch (ExpiredJwtException e) {
            return false;
        }
    }

    public Integer getUserIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .setSigningKey(jwtKey)
                .parseClaimsJws(token)
                .getBody();
        return Integer.parseInt(claims.getSubject());
    }
    @Transactional
    public void deleteToken(String tokenContent) {
        tokenDAO.deleteByContent(tokenContent);
    }
    @Transactional
    public void deleteAllTokens(Integer userID) {
        tokenDAO.deleteAllTokens(userID);
    }

    public boolean isTokenExpired(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(jwtKey).build().parseClaimsJws(token);
            return false;
        } catch (ExpiredJwtException e) {
            // Token is expired
            return true;
        } catch (Exception e) {
            return true;
        }
    }

}