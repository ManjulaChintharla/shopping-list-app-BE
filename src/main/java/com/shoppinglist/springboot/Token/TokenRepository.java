package com.shoppinglist.springboot.Token;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TokenRepository extends JpaRepository < Token, Integer > {
    void deleteByContent(String tokenContent);
    void deleteAllByUserID(Integer UserID);
    Optional < Token > findByContent(String token);

}