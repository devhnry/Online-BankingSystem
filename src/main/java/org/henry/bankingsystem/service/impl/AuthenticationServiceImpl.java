package org.henry.bankingsystem.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.henry.bankingsystem.dto.*;
import org.henry.bankingsystem.entity.Account;
import org.henry.bankingsystem.entity.AuthToken;
import org.henry.bankingsystem.entity.Customer;
import org.henry.bankingsystem.entity.OneTimePassword;
import org.henry.bankingsystem.enums.AccountType;
import org.henry.bankingsystem.enums.ContextType;
import org.henry.bankingsystem.enums.CurrencyType;
import org.henry.bankingsystem.enums.VerifyOtpResponse;
import org.henry.bankingsystem.exceptions.ResourceNotFoundException;
import org.henry.bankingsystem.repository.AccountRepository;
import org.henry.bankingsystem.repository.OtpRepository;
import org.henry.bankingsystem.repository.TokenRepository;
import org.henry.bankingsystem.repository.UserRepository;
import org.henry.bankingsystem.service.AuthenticationService;
import org.henry.bankingsystem.service.EmailService;
import org.henry.bankingsystem.service.JWTService;
import org.henry.bankingsystem.utils.OneTimePasswordUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.henry.bankingsystem.constants.StatusCodeConstants.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TokenRepository tokenRepository;
    private final OtpRepository otpRepository;
    private final JWTService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;
    private final OneTimePasswordUtil oneTimePasswordUtil;

    private final static BigDecimal DEFAULT_TRANSACTION_LIMIT = BigDecimal.valueOf(200_000.00);
    private final static BigDecimal DEFAULT_INTEREST_RATE = BigDecimal.valueOf(4);

    private record generateAccessTokenAndRefreshToken(String accessToken, String refreshToken) {}

    @Override
    public DefaultApiResponse<SuccessfulOnboardDto> onBoard(OnboardUserDto requestBody) {
        DefaultApiResponse<SuccessfulOnboardDto> response = new DefaultApiResponse<>();
        SuccessfulOnboardDto responseData;

        Account newAccount = new Account();
        Customer newCustomer = new Customer();

        try {
            // Log the start of the onboarding process
            log.info("Starting the onboarding process for {}", requestBody.email());

            boolean customerAlreadyExists = userRepository.existsByEmail(requestBody.email());

            if (customerAlreadyExists) {
                log.info("Customer with email {} already exists.", requestBody.email());
                response.setStatusCode(ONBOARDING_DUPLICATE_EMAIL);
                response.setStatusMessage("Customer Already Exists: Try Logging in");
                return response;
            }

            if (!(requestBody.initialDeposit().compareTo(BigDecimal.valueOf(5000.00)) >= 0)) {
                log.warn("Initial deposit {} is less than the minimum required amount of 5000.", requestBody.initialDeposit());
                response.setStatusCode(ONBOARDING_FAILED);
                response.setStatusMessage("An account need to be created with an Initial Deposit of MIN 5000.");
                return response;
            }

            if (!verifyPasswordStrength(requestBody.password())) {
                log.info("Password not strong enough for user {}.", requestBody.email());
                response.setStatusCode(VALIDATION_ERROR);
                response.setStatusMessage("Password should contain at least 8 characters, at least One Uppercase letter, numbers and a symbol");
                return response;
            }

            if(String.valueOf(requestBody.hashedPin()).length() > 4) {
                response.setStatusCode(VALIDATION_ERROR);
                response.setStatusMessage("Account HashedPin must be exactly 4 digits");
                return response;
            }

            // Calls Method to validate that Enum Type is Correct
            validateEnumTypes(requestBody);

            // Generate customer and account based on the request data
            newCustomer = generateCustomerAndAccount(newCustomer, newAccount, requestBody);
            responseData = setResponseData(newCustomer.getAccounts().getFirst(), newCustomer);

        }catch (HttpMessageConversionException e){
            throw new HttpMessageConversionException(e.getMessage());
        } catch (RuntimeException e) {
            log.error("An Error occurred while performing OnBoarding :{}",e.getMessage());
            throw new RuntimeException(e.getMessage());
        }
        // Log successful onboarding
        log.info("Customer successfully onboarded: {}", newCustomer.getEmail());

        log.info("Onboarding Email has been sent to the Customer");

        response.setStatusCode(ONBOARDING_SUCCESS);
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

            Customer customer;
            Optional<Customer> customerOptional = userRepository.findByEmail(requestBody.email());
            if(customerOptional.isPresent()){
                customer = customerOptional.get();

                // Prevents the customer from login in if account has not been verified.
                if(customer.getIsEnabled().equals(false)){
                    log.warn("Customer account has not been verified {}", requestBody.email());
                    response.setStatusCode(RESOURCE_NOT_FOUND_EXCEPTION);
                    response.setStatusMessage("Your Account has not been verified.");
                    return response;
                }

                log.info("User Found on the DB with email {}.", requestBody.email());

                if(!passwordEncoder.matches(requestBody.password(), customer.getPassword())){
                    log.warn("Invalid Password for user {}.", requestBody.email());
                    response.setStatusCode(LOGIN_INVALID_CREDENTIALS);
                    response.setStatusMessage("Invalid Password");
                    return response;
                }
            } else {
                log.warn("User with email {} not found in the database.", requestBody.email());
                response.setStatusCode(DATABASE_ERROR);
                response.setStatusMessage("Customer Not Found: OnBoard on the System or Verify Email");
                return response;
            }

            // Generate access and refresh tokens for the authenticated customer
            generateAccessTokenAndRefreshToken result = getGenerateAccessTokenAndRefreshToken(customer);

            AuthorisationResponseDto authorisationResponseDto = new AuthorisationResponseDto(
                    result.accessToken(), result.refreshToken(), getLastUpdatedAt() , "24hrs");

            // Authenticate the user with the provided credentials
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(requestBody.email(), requestBody.password()));

            response.setStatusCode(LOGIN_SUCCESS);
            response.setStatusMessage("Successfully Logged In");
            response.setData(authorisationResponseDto);
            log.info("User {} successfully logged in.", requestBody.email());

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
                response.setStatusCode(GENERIC_ERROR);
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

                    response.setStatusCode(REFRESH_TOKEN_SUCCESS);
                    response.setStatusMessage("Successfully Refreshed AuthToken");
                    AuthorisationResponseDto responseDto = new AuthorisationResponseDto(
                            newAccessToken, newRefreshToken, getLastUpdatedAt() , "24hrs");
                    response.setData(responseDto);
                } else {
                    log.warn("Invalid Token signature for user {}.", userEmail);
                }
            }
        } catch (RuntimeException ex){
            log.error("An error occurred while refreshing the token: {}", ex.getMessage());
        }
        return response;
    }

    @Override
    public DefaultApiResponse<OneTimePasswordDto> sendOtp(String customerEmail) {
        return oneTimePasswordUtil.sendOtp(customerEmail, ContextType.ONBOARDING);
    }

    @Override
    public DefaultApiResponse<AuthorisationResponseDto> verifyOtp(VerifyOtpRequest requestBody) {
        DefaultApiResponse<AuthorisationResponseDto> response = new DefaultApiResponse<>();
        VerifyOtpResponse verifyOtp = oneTimePasswordUtil.verifyOtp(requestBody.otpCode(), requestBody.email());

        if(!verifyOtp.equals(VerifyOtpResponse.VERIFIED)){
            response.setStatusCode(OTP_INVALID);
            response.setStatusMessage("Invalid OTP: Resend OTP");
            return response;
        }

        OneTimePassword oneTimePassword = new OneTimePassword();
        Optional<OneTimePassword> existingOtpOpt = otpRepository.findByOtpCode(requestBody.otpCode());
        if(existingOtpOpt.isPresent()){
            oneTimePassword = existingOtpOpt.get();
        }

        // Generate access and refresh tokens for the authenticated customer
        Customer customer = oneTimePassword.getCustomer();
        customer.setIsEnabled(true);
        userRepository.save(customer);
        generateAccessTokenAndRefreshToken result = getGenerateAccessTokenAndRefreshToken(customer);
        AuthorisationResponseDto responseDto = new AuthorisationResponseDto(
                result.accessToken, result.refreshToken,  getLastUpdatedAt(), "24hrs"
        );

        // Authenticate the user with the provided credentials
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(requestBody.email(), requestBody.password()));

        // Sends Email after Successful Onboarding Process
        try{
            Context emailContext = getEmailContext(customer);
            emailService.sendEmail(customer.getEmail().trim(), "Welcome to EasyBanking", emailContext, "onboardTemplate");
        }catch (RuntimeException e){
            log.error("Error Occurred in sending Onboarding email after Three tries");
        }
        return createSuccessResponse(response, requestBody.email(), responseDto);
    }

    private String getLastUpdatedAt(){
        return LocalDateTime.now().toString().replace("T", " ").substring(0, 16);
    }

    // Sends SUCCESS Response for OTP validation Method
    private DefaultApiResponse<AuthorisationResponseDto> createSuccessResponse(DefaultApiResponse<AuthorisationResponseDto> response, String email, AuthorisationResponseDto data) {
        response.setStatusCode(SUCCESS);
        response.setStatusMessage(String.format("Successfully Verified OTP for user %s [User has been logged in]", email));
        response.setData(data);
        return response;
    }


    // Gets the Customer's Details and Assign it to the Variable on the Email Template
    private Context getEmailContext(Customer customer){
        Context emailContext = new Context();

        Account account = accountRepository.findAccountByCustomer_CustomerId(customer.getCustomerId()).orElseThrow(
                () -> new ResourceNotFoundException("Customer Not Found: Customer Id: " + customer.getCustomerId())
        );

        emailContext.setVariable("name", customer.getFirstName() + " " + customer.getLastName());
        emailContext.setVariable("accountHolderName", account.getAccountHolderName());
        emailContext.setVariable("accountNumber", account.getAccountNumber() );
        emailContext.setVariable("accountType", account.getAccountType());

        log.info("Details have been applied to Email Context");
        return emailContext;
    }

    // Gets the Customer's Details and takes the OTP and Assign it to the Variable on the Email Template
    private Context generateEmailContextForOTPValidation(Customer customer, OneTimePassword otp){
        Context emailContext = new Context();

        Account account = accountRepository.findAccountByCustomer_CustomerId(customer.getCustomerId()).orElseThrow(
                () -> new ResourceNotFoundException("Customer Not Found: Customer Id: " + customer.getCustomerId())
        );

        emailContext.setVariable("name", customer.getFirstName() + " " + customer.getLastName());
        emailContext.setVariable("duration", otp.getExpiresDuration());
        emailContext.setVariable("otpCode", otp.getOtpCode());

        log.info("Details of OTP and Customer have been applied to Email Context");
        return emailContext;
    }

    private static void validateEnumTypes(OnboardUserDto requestBody) {
        log.info("Validating Account and Currency Type");
        List<String> currencyTypes = new ArrayList<>();
        List<String> accountTypes = new ArrayList<>();
        for(AccountType accountType : AccountType.values()) {
            accountTypes.add(accountType.name());
        }
        for(CurrencyType currencyType : CurrencyType.values()) {
            currencyTypes.add(currencyType.name());
        }
        if(!accountTypes.contains(requestBody.accountType())) {
            log.warn("INVALID ACCOUNT TYPE {} for user {}.", requestBody.accountType(), requestBody.email());
            throw new HttpMessageConversionException(String.format("Account type %s is not supported. (%s).", requestBody.accountType(), Arrays.toString(AccountType.values())));
        }
        if(!currencyTypes.contains(requestBody.currencyType())) {
            log.warn("INVALID CURRENCY_TYPE {} for user {}.", requestBody.currencyType(), requestBody.email());
            throw new HttpMessageConversionException(String.format("Currency type %s is not supported. (%s).", requestBody.currencyType(), Arrays.toString(CurrencyType.values())));
        }
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
        String accountNumber;
        String uniqueNumber;
        /*
         * Generates a random number from the characters
         * Adds the uniqueValue to the end of uniqueNumber which changes as number of users increases
         */
        do{
            uniqueNumber = RandomStringUtils.random(10 , "0123456789");
            int subStringEnd = (10 - String.valueOf(uniqueValue).length());
            accountNumber = uniqueNumber.substring(0, subStringEnd) + uniqueValue;
        }while(accountRepository.existsByAccountNumber(uniqueNumber + uniqueValue));

        return accountNumber;
    }

    private Customer generateCustomerAndAccount(Customer newCustomer, Account newAccount, OnboardUserDto requestBody){
        String accountNumber = generateAccountNumber();
        String fullName = String.format("%s %s", requestBody.firstName().trim(), requestBody.lastName().trim());

        // Generates new Account from the RequestBody
        log.info("Generating Account for Customer");
        try {
            newAccount = Account.builder()
                    .accountNumber(accountNumber)
                    .accountHolderName(fullName)
                    .accountType(AccountType.valueOf(requestBody.accountType()))
                    .accountBalance(requestBody.initialDeposit())
                    .transactionLimit(DEFAULT_TRANSACTION_LIMIT)
                    .dateOpened(Instant.now())
                    .isActive(true)
                    .hashedPin(passwordEncoder.encode(String.valueOf(requestBody.hashedPin())))
                    .currencyType(CurrencyType.valueOf(requestBody.currencyType()))
                    .interestRate(DEFAULT_INTEREST_RATE)
                    .lastTransactionDate(LocalDateTime.now())
                    .build();
            log.info("Account created: {}", newAccount);
        } catch (RuntimeException ex) {
            log.error("Error creating account for Customer {}", ex.getMessage());
        }

        // Generates new Customer from the RequestBody
        log.info("Generating Customer Details for Customer");
        try {
            newCustomer = Customer.builder()
                    .firstName(requestBody.firstName().trim())
                    .lastName(requestBody.lastName().trim())
                    .username(requestBody.email())
                    .email(requestBody.email())
                    .password(passwordEncoder.encode(requestBody.password()))
                    .phoneNumber(requestBody.phoneNumber())
                    .isSuspended(false)
                    .isEnabled(false)
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