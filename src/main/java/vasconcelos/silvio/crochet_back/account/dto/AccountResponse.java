package vasconcelos.silvio.crochet_back.account.dto;

import vasconcelos.silvio.crochet_back.account.Account;

import java.time.Instant;

public record AccountResponse(Long id, String email, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getEmail(), account.getCreatedAt());
    }
}
