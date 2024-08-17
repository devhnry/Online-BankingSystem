package org.henry.onlinebankingsystemp.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.henry.onlinebankingsystemp.dto.*;
import org.henry.onlinebankingsystemp.entity.Account;
import org.henry.onlinebankingsystemp.entity.AuthToken;
import org.henry.onlinebankingsystemp.entity.Customer;
import org.henry.onlinebankingsystemp.repository.AccountRepository;
import org.henry.onlinebankingsystemp.repository.TokenRepository;
import org.henry.onlinebankingsystemp.repository.UserRepository;
import org.henry.onlinebankingsystemp.service.AuthenticationService;
import org.henry.onlinebankingsystemp.service.JWTService;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TokenRepository tokenRepository;
    private final JWTService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    private final static BigDecimal DEFAULT_TRANSACTION_LIMIT = BigDecimal.valueOf(200_000.00);
    private final static BigDecimal DEFAULT_INTEREST_RATE = BigDecimal.valueOf(4);

    private record generateAccessTokenAndRefreshToken(String accessToken, String refreshToken) {}

    @Override
    public DefaultApiResponse<SuccessfulOnboardDto> onBoard(OnboardUserDto requestBody) {
        DefaultApiResponse<SuccessfulOnboardDto> response = new DefaultApiResponse<>();
        SuccessfulOnboardDto responseData = new SuccessfulOnboardDto();

        Account newAccount = new Account();
        Customer newCustomer = new Customer();

        try {
            // Log the start of the onboarding process
            log.info("Starting the onboarding process for {}", requestBody.email());

            // Validate the onboarding request data
            OnboardUserDto.validate(requestBody);
            boolean customerAlreadyExists = userRepository.existsByEmail(requestBody.email());

            if (customerAlreadyExists) {
                log.info("Customer with email {} already exists.", requestBody.email());
                response.setStatusCode(200);
                response.setStatusMessage("Customer Already Exists: Try Logging in");
                return response;
            }

            if(!(requestBody.initialDeposit().compareTo(BigDecimal.valueOf(5000.00)) >= 0)){
                log.warn("Initial deposit {} is less than the minimum required amount of 5000.", requestBody.initialDeposit());
                response.setStatusCode(400);
                response.setStatusMessage("An account need ot be created with an Initial Deposit of MIN 5000.");
                return response;
            }

            if (!verifyPasswordStrength(requestBody.password())) {
                log.info("Password not strong enough for user {}.", requestBody.email());
                response.setStatusCode(400);
                response.setStatusMessage("Password should contain at least 8 characters, numbers and a symbol");
                return response;
            }

            // Generate customer and account based on the request data
            newCustomer = generateCustomerAndAccount(newCustomer, newAccount, requestBody);
            responseData = setResponseData(newCustomer.getAccounts().getFirst(), newCustomer);

        } catch (IllegalArgumentException e) {
            log.error("Invalid Argument during Onboarding: {}", e.getMessage());
            throw new IllegalArgumentException(e.getMessage());
        } catch (RuntimeException e) {
            log.error("An Error occurred while performing OnBoarding :{}",e.getMessage());
        }

        // Log successful onboarding
        log.info("Customer successfully onboarded: {}", newCustomer.getEmail());

        response.setStatusCode(HttpStatus.CREATED.value());
        response.setStatusMessage("Customer Successfully Onboarded");
        response.setData(responseData);

        return response;
    }

    @Override
    public DefaultApiResponse<AuthorisationResponseDto> login(LoginRequestDto requestBody) {
        DefaultApiResponse<AuthorisationResponseDto> response = new DefaultApiResponse<>();
        log.info("Performing Authentication and Processing Login Request for user {}.", requestBody.email());
        try {
            // Validate the login request data
            LoginRequestDto.validate(requestBody);

            Customer customer = new Customer();
            Optional<Customer> customerOptional = userRepository.findByEmail(requestBody.email());
            if(customerOptional.isPresent()){
                customer = customerOptional.get();
                log.info("User Found on the DB with email {}.", requestBody.email());

                if(!passwordEncoder.matches(requestBody.password(), customer.getPassword())){
                    log.warn("Invalid Password for user {}.", requestBody.email());
                    response.setStatusCode(400);
                    response.setStatusMessage("Invalid Password");
                    return response;
                }
            } else {
                log.warn("User with email {} not found in the database.", requestBody.email());
                response.setStatusCode(400);
                response.setStatusMessage("Customer Not Found: OnBoard on the System or Verify Email");
                return response;
            }

            // Generate access and refresh tokens for the authenticated customer
            generateAccessTokenAndRefreshToken result = getGenerateAccessTokenAndRefreshToken(customer);

            AuthorisationResponseDto authorisationResponseDto = new AuthorisationResponseDto(
                    result.accessToken(), result.refreshToken(), Instant.now(), "24hrs");

            // Authenticate the user with the provided credentials
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(requestBody.email(), requestBody.password()));

            response.setStatusCode(HttpStatus.OK.value());
            response.setStatusMessage("Successfully Logged In");
            response.setData(authorisationResponseDto);
            log.info("User {} successfully logged in.", requestBody.email());

        } catch (IllegalArgumentException e) {
            log.error("Invalid Argument during Login for user {}: {}", requestBody.email(), e.getMessage());
            throw new IllegalArgumentException(e.getMessage());
        } catch (RuntimeException ex){
            log.error("An error occurred while performing Authentication for user {}: {}", requestBody.email(), ex.getMessage());
        }
        return response;
    }

    @Override
    public DefaultApiResponse<AuthorisationResponseDto> refreshToken(RefreshTokenDto requestBody) {
        log.info("Processing Refreshing Token Request for user.");
        DefaultApiResponse<AuthorisationResponseDto> response = new DefaultApiResponse<>();

        try {
            // Validate the refresh token request data
            RefreshTokenDto.validate(requestBody);

            String userEmail = jwtService.extractUsername(requestBody.refreshToken());
            log.info("Email of the Refresh Token: {}", userEmail);

            log.info("Checking if Refresh token has expired.");
            if(jwtService.isTokenExpired(requestBody.refreshToken())){
                response.setStatusCode(200);
                response.setStatusMessage("Refresh Token Expired: User needs to Log in Again");
                log.warn("Refresh Token has expired for user {}: {}", userEmail, requestBody.refreshToken());
                return response;
            }

            Optional<Customer> existingCustomer = userRepository.findByEmail(userEmail);
            if(existingCustomer.isPresent()){
                Customer customer = existingCustomer.get();

                log.info("Verifying Token is valid and properly signed for user {}.", userEmail);
                if(jwtService.isTokenValid(requestBody.refreshToken(), customer)){
                    log.info("Generating New Token for user {}.", userEmail);

                    String newAccessToken = jwtService.createJWT(customer);
                    String newRefreshToken = jwtService.generateRefreshToken(generateRefreshTokenClaims(customer), customer);

                    // Revoke old tokens and save the new tokens
                    revokeOldTokens(customer);
                    saveCustomerToken(customer, newAccessToken, newRefreshToken);

                    response.setStatusCode(HttpStatus.CREATED.value());
                    response.setStatusMessage("Successfully Refreshed AuthToken");
                    AuthorisationResponseDto responseDto = new AuthorisationResponseDto(
                            newAccessToken, newRefreshToken, Instant.now(), "24hrs");
                    response.setData(responseDto);
                } else {
                    log.warn("Invalid Token signature for user {}.", userEmail);
                }
            }

        } catch (IllegalArgumentException e) {
            log.error("Invalid Argument during Refreshing Token: {}", e.getMessage());
            throw new IllegalArgumentException(e.getMessage());
        } catch (RuntimeException ex){
            log.error("An error occurred while refreshing the token: {}", ex.getMessage());
        }
        return response;
    }

    private void saveCustomerToken(Customer customer, String jwtToken, String refreshToken){
        // Log the process of saving tokens
        log.info("Saving tokens for customer {}", customer.getEmail());

        // Save the generated access and refresh tokens for the customer
        AuthToken token = AuthToken.builder()
                .customer(customer)
                .accessToken(jwtToken)
                .refreshToken(refreshToken)
                .expired(false)
                .revoked(false)
                .build();
        tokenRepository.save(token);

        // Log successful token saving
        log.info("Saved Access and Refresh tokens for customer {}", customer.getEmail());
    }

    private void revokeOldTokens(Customer customer){
        // Log the process of revoking old tokens
        log.info("Revoking old tokens for customer {}", customer.getEmail());

        // Revoke all old tokens for the customer
        List<AuthToken> validTokens = tokenRepository.findValidTokenByCustomer(customer.getCustomerId());
        if (validTokens.isEmpty()){
            log.info("No valid tokens found for customer {}.", customer.getEmail());
            return;
        }
        validTokens.forEach(token -> {
            token.setRevoked(true);
            token.setExpired(true);
        });
        tokenRepository.saveAll(validTokens);

        // Log successful token revocation
        log.info("Revoked old tokens for customer {}.", customer.getEmail());
    }

    // Method to Assign New Customer and Account to SuccessOnboardDto Response
    private SuccessfulOnboardDto setResponseData(Account newAccount, Customer newCustomer) {
        log.info("Setting response data for successful onboarding of customer {}", newCustomer.getEmail());
        SuccessfulOnboardDto responseData = new SuccessfulOnboardDto();

        // Creates a Mock Value of the account
        AccountDto accountDto = new AccountDto();
        accountDto.setAccountId(newAccount.getAccountId());
        accountDto.setAccountHolderName(newAccount.getAccountHolderName());
        accountDto.setAccountNumber(newAccount.getAccountNumber());
        accountDto.setAccountType(newAccount.getAccountType());
        accountDto.setCurrencyType(newAccount.getCurrencyType());
        accountDto.setBalance(newAccount.getAccountBalance());

        // Creates Response Data Body
        responseData.setCustomerId(newCustomer.getCustomerId());
        responseData.setFirstName(newCustomer.getFirstName());
        responseData.setLastName(newCustomer.getLastName());
        responseData.setEmail(newCustomer.getEmail());
        responseData.setPhoneNumber(newCustomer.getPhoneNumber());
        responseData.setAccount(accountDto);

        return responseData;
    }

    /* Generates Account Number for Customer */
    private String generateAccountNumber() {
        log.info("Generating Account Number for Customer");
        long uniqueValue = userRepository.count();
        String uniqueNumber = "";
        /*
         * Generates a random number from the characters
         * Adds the uniqueValue to the end of uniqueNumber which changes as number of users increases
         */
        do{
            uniqueNumber = RandomStringUtils.random((int) (uniqueValue % 10), "0123456789");
        }while(accountRepository.existsByAccountNumber(uniqueNumber + uniqueValue));

        return uniqueNumber + uniqueValue;
    }

    private Customer generateCustomerAndAccount(Customer newCustomer, Account newAccount, OnboardUserDto requestBody){
        String accountNumber = generateAccountNumber();
        String fullName = String.format("%s %s", requestBody.firstName(), requestBody.lastName());

        // Generates new Account from the RequestBody
        log.info("Generating Account for Customer");
        try {
            newAccount = Account.builder()
                    .accountNumber(accountNumber)
                    .accountHolderName(fullName)
                    .accountType(requestBody.accountType())
                    .accountBalance(requestBody.initialDeposit())
                    .transactionLimit(DEFAULT_TRANSACTION_LIMIT)
                    .dateOpened(Instant.now())
                    .isActive(true)
                    .currencyType(requestBody.currencyType())
                    .interestRate(DEFAULT_INTEREST_RATE)
                    .lastTransactionDate(Instant.now())
                    .build();
            log.info("Account created: {}", newAccount);
        } catch (RuntimeException ex) {
            log.error("Error creating account for Customer {}", ex.getMessage());
        }

        // Generates new Customer from the RequestBody
        log.info("Generating Customer Details for Customer");
        try {
            newCustomer = Customer.builder()
                    .firstName(requestBody.firstName())
                    .lastName(requestBody.lastName())
                    .username(requestBody.email())
                    .email(requestBody.email())
                    .password(passwordEncoder.encode(requestBody.password()))
                    .phoneNumber(requestBody.phoneNumber())
                    .isSuspended(false)
                    .accounts(new ArrayList<>())  // Initializes the accounts list
                    .build();

            newCustomer.getAccounts().add(newAccount);
            newAccount.setCustomer(newCustomer);

            userRepository.save(newCustomer); // This will cascade and save the account too
            log.info("Customer created successfully with Account");
        } catch (RuntimeException ex) {
            log.error("Error generating customer entity {}", ex.getMessage());
        }

        return newCustomer;
    }

    private @NotNull generateAccessTokenAndRefreshToken getGenerateAccessTokenAndRefreshToken(Customer customer){
        // Log the token generation process
        log.info("Generating Access Token and Refresh Token for Customer");

        String jwtToken = jwtService.createJWT(customer);
        String refreshToken = jwtService.generateRefreshToken(generateRefreshTokenClaims(customer), customer);

        saveCustomerToken(customer, jwtToken, refreshToken);
        return new generateAccessTokenAndRefreshToken(jwtToken, refreshToken);
    }

    private static boolean verifyPasswordStrength(String password) {
        // Log the password strength verification process
        log.info("Verifying password strength");

        String regex = "^(?=.*[A-Z])(?=.*[0-9])(?=.*[!@#&()–[{}]:;',?/*~$^+=<>]).{8,20}$";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(password);
        return matcher.matches();
    }

    private @NotNull HashMap<String, Object> generateRefreshTokenClaims(Customer customer){
        // Log the process of generating refresh token claims
        log.info("Generating Refresh Token Claims");

        HashMap<String, Object> claims = new HashMap<>();
        claims.put("username", customer.getUsername());
        claims.put("email", customer.getEmail());
        claims.put("customerId", customer.getCustomerId());
        return claims;
    }
}