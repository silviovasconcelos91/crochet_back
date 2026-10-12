package vasconcelos.silvio.crochet_back.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vasconcelos.silvio.crochet_back.Timestamps;

import java.time.Instant;

@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 30)
    private String username;

    @Column(name = "username_key", nullable = false, length = 60)
    private String usernameKey;

    @Setter
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** {@code username} and {@code email} must already be normalized ({@link Usernames}, {@link Emails}). */
    public Account(String username, String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        rename(username);
    }

    /** {@code username} must already be normalized; its key is derived here so the two never diverge. */
    public void rename(String username) {
        this.username = username;
        this.usernameKey = Usernames.key(username);
    }

    @PrePersist
    void onCreate() {
        createdAt = Timestamps.now();
    }
}
