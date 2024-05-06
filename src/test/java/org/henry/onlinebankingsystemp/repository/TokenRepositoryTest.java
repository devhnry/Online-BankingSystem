package org.henry.onlinebankingsystemp.repository;

import jakarta.persistence.EntityManager;
import net.bytebuddy.utility.dispatcher.JavaDispatcher;
import org.henry.onlinebankingsystemp.controller.AccountController;
import org.henry.onlinebankingsystemp.dto.enums.TokenType;
import org.henry.onlinebankingsystemp.entity.Customer;
import org.henry.onlinebankingsystemp.entity.Token;
import org.henry.onlinebankingsystemp.service.AccountService;
import org.henry.onlinebankingsystemp.service.JWTService;
import org.henry.onlinebankingsystemp.service.UserDetailService;
import org.henry.onlinebankingsystemp.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.runners.Parameterized;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TokenRepositoryTest {
    @MockBean private RestTemplate restTemplate;
    @Autowired
    private TestEntityManager testEntityManager;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private TokenRepository underTest;

    static MySQLContainer<?> container =
            new MySQLContainer<>()
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("s3cret")
                    .withReuse(true);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.datasource.username", container::getUsername);
    }


    static {
        container.start();
    }

//    @AfterEach
//    void setup() {
//        container.stop();
//    }

    @Test
    void notNull() throws Exception{
        assertNotNull(underTest);
        assertNotNull(entityManager);
        assertNotNull(dataSource);
        assertNotNull(testEntityManager);

        System.out.println(dataSource.getConnection().getMetaData().getDatabaseProductName());

        Token token = new Token();
        token.setId(1);
        token.setRevoked(false);
        token.setExpired(false);
        token.setToken("kjvslncmvlksnmkzjsvsz");
        token.setTokenType(TokenType.BEARER);
        token.setAdmin(null);
        token.setUsers(null);

        Token result = underTest.save(token);
        assertNotNull(result.getId());
    }

    @Test
    void findValidTokenByCustomer() {
    }

    @Test
    void findValidTokenByAdmin() {
    }
}