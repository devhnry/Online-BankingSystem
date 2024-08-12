package org.henry.onlinebankingsystemp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.henry.onlinebankingsystemp.dto.DefaultApiResponse;
import org.henry.onlinebankingsystemp.dto.LoginRequestDto;
import org.henry.onlinebankingsystemp.dto.OnboardUserDto;
import org.henry.onlinebankingsystemp.dto.RefreshTokenDto;
import org.henry.onlinebankingsystemp.dto2.*;
import org.henry.onlinebankingsystemp.entity.Admin;
import org.henry.onlinebankingsystemp.entity.Customer;
import org.henry.onlinebankingsystemp.factory.AccountFactory;
import org.henry.onlinebankingsystemp.repository.AdminRepository;
import org.henry.onlinebankingsystemp.repository.TokenRepository;
import org.henry.onlinebankingsystemp.repository.UserRepository;
import org.henry.onlinebankingsystemp.entity.AuthToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthenticationService {
    private final UserRepository userRepository;
    private final JWTService jwtService;
    private final AuthenticationManager authenticationManager;
    private final TokenRepository tokenRepository;
    private final AdminRepository adminRepository;
    private final AccountFactory accountFactory;

    public DefaultApiResponse signUp(OnboardUserDto request){
        return accountFactory.createAccount(request);
    }

    public LoginResponseDTO login(LoginRequestDto request) {
        LoginResponseDTO res = new LoginResponseDTO();
        log.info("Performing Authentication");
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));

            Optional<Admin> optionalAdmin = adminRepository.findByEmail(request.getEmail());
            if (optionalAdmin.isPresent()) {
                var admin = optionalAdmin.orElseThrow();
                var jwtToken = jwtService.generateToken(admin);
                jwtService.generateRefreshToken(new HashMap<>(), admin);

                res.setStatusCode(200);
                res.setEmail(request.getEmail());
                res.setToken(jwtToken);
                res.setRefreshToken(jwtToken);
                res.setExpirationTime("24hr");
                res.setMessage("Successfully Logged In");
                log.info(res.getMessage());

                revokeAllAdminTokens(admin);
                saveAdminToken(admin, jwtToken);
            } else {
                boolean optionalUser = userRepository.findByEmail(request.getEmail()).isPresent();
                if(!optionalUser){
                    res.setStatusCode(404);
                    res.setMessage("Customer or Admin not found");
                    return res;
                }
                var user = userRepository.findByEmail(request.getEmail()).orElseThrow();
                if(user.getIsSuspended()){
                    res.setMessage("Your account has been suspended");
                    res.setStatusCode(500);
                    return res;
                }

                var jwtToken = jwtService.generateToken(user);
                jwtService.generateRefreshToken(new HashMap<>(), user);

                res.setStatusCode(200);
                res.setEmail(request.getEmail());
                res.setToken(jwtToken);
                res.setRefreshToken(jwtToken);
                res.setExpirationTime("24hr");
                res.setMessage("Successfully Signed In");
                log.info("Logged In Successfully");

                revokeAllUserTokens(user);
                saveUserToken(user, jwtToken);
            }
        } catch (Exception e) {
            res.setStatusCode(500);
            res.setMessage(e.getMessage());
        }
        return res;
    }

    public LoginResponseDTO refreshToken(RefreshTokenDto refreshTokenRequest){
        LoginResponseDTO res = new LoginResponseDTO();
        String userEmail = jwtService.extractUsername(refreshTokenRequest.getToken());
        Customer customer = userRepository.findByEmail(userEmail).orElseThrow();

        try {
            Optional<Customer> optionalUser = userRepository.findByEmail(userEmail);
            if(optionalUser.isPresent()){
                if(jwtService.isTokenValid(refreshTokenRequest.getToken(), customer)){
                    var newToken = jwtService.generateToken(customer);

                    revokeAllUserTokens(customer);
                    saveUserToken(customer,newToken);

                    res.setStatusCode(200);
                    res.setToken(newToken);
                    res.setRefreshToken(refreshTokenRequest.getToken());
                    res.setExpirationTime("24hr");
                    res.setMessage("Successfully Refreshed AuthToken");
                }
            }
            else {
                Admin admin = adminRepository.findByEmail(userEmail).orElseThrow();
                Optional<Admin> optionalAdmin = adminRepository.findByEmail(userEmail);
                if (optionalAdmin.isPresent()) {
                    if(jwtService.isTokenValid(refreshTokenRequest.getToken(), admin)){
                        var newToken = jwtService.generateToken(admin);

                        revokeAllUserTokens(customer);
                        saveUserToken(customer,newToken);

                        res.setStatusCode(200);
                        res.setToken(newToken);
                        res.setRefreshToken(refreshTokenRequest.getToken());
                        res.setExpirationTime("24hr");
                        res.setMessage("Successfully Refreshed AuthToken");
                    }
                }
            }
        } catch (Exception e) {
            res.setStatusCode(500);
            res.setMessage(e.getMessage());
        }
        return res;
    }

    public void saveUserToken(Customer customer, String newToken) {
        var token = AuthToken.builder()
                .users(customer)
                .token(newToken)
                .tokenType(TokenType.BEARER)
                .expired(false)
                .revoked(false)
                .build();

        tokenRepository.save(token);
    }

    public void saveAdminToken(Admin admin, String newToken){
        var token = AuthToken.builder()
                .admin(admin)
                .token(newToken)
                .tokenType(TokenType.BEARER)
                .expired(false)
                .revoked(false)
                .build();

        tokenRepository.save(token);
    }

    public void revokeAllUserTokens(Customer customer){
        var validUserTokens = tokenRepository.findValidTokenByCustomer(customer.getCustomerId());
        if(validUserTokens.isEmpty())
            return;
        validUserTokens.forEach(t -> {
            t.setExpired(true);
            t.setRevoked(true);
        });
        tokenRepository.saveAll(validUserTokens);
    }

    public void revokeAllAdminTokens(Admin admin){
        var validAdminToken = tokenRepository.findValidTokenByAdmin(admin.getAdminId());
        System.out.println(validAdminToken);
        if(validAdminToken.isEmpty())
            return;
        validAdminToken.forEach(t -> {
            t.setExpired(true);
            t.setRevoked(true);
        });
        tokenRepository.saveAll(validAdminToken);
    }
}
