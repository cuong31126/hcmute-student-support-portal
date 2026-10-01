package com.school.counseling.module.auth.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
class AuthWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Truy cập trang /auth/login trả về 200 OK")
    void shouldRenderLoginPageSuccessfully() throws Exception {
        mockMvc.perform(get("/auth/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    @Test
    @DisplayName("Truy cập trang /auth/login?error=true trả về 200 OK")
    void shouldRenderLoginPageWithErrorParamSuccessfully() throws Exception {
        mockMvc.perform(get("/auth/login").param("error", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    @Test
    @DisplayName("Truy cập trang /auth/login?logout=true trả về 200 OK")
    void shouldRenderLoginPageWithLogoutParamSuccessfully() throws Exception {
        mockMvc.perform(get("/auth/login").param("logout", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    @Test
    @DisplayName("Truy cập trang /auth/register trả về 200 OK")
    void shouldRenderRegisterPageSuccessfully() throws Exception {
        mockMvc.perform(get("/auth/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"));
    }

    @Test
    @DisplayName("Chu trình: Đăng nhập -> Đăng xuất -> Đăng nhập lại thành công")
    void shouldLoginLogoutAndLoginAgainSuccessfully() throws Exception {
        // 1. Đăng nhập lần đầu với admin
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .param("username", "admin")
                        .param("password", "123456")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

        // 2. Đăng xuất
        MvcResult logoutResult = mockMvc.perform(post("/auth/logout")
                        .session(session)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login?logout=true"))
                .andReturn();

        // 3. Mở trang login sau khi logout
        mockMvc.perform(get("/auth/login").param("logout", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));

        // 4. Đăng nhập lại
        mockMvc.perform(post("/auth/login")
                        .param("username", "admin")
                        .param("password", "123456")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
