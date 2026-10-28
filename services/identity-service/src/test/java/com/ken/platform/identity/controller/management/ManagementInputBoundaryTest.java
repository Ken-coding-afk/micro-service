package com.ken.platform.identity.controller.management;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ken.platform.foundation.web.ServletApiErrors;
import com.ken.platform.identity.service.account.AccountCreationService;
import com.ken.platform.identity.service.account.OwnAccountService;
import com.ken.platform.identity.service.account.PlatformAccountQueryService;
import com.ken.platform.identity.service.account.PlatformLifecycleService;
import com.ken.platform.identity.service.oauth.ClientManagementService;
import com.ken.platform.identity.service.role.RoleAssignmentService;
import com.ken.platform.identity.service.role.RoleManagementService;
import com.ken.platform.identity.service.role.RolePermissionService;
import com.ken.platform.identity.service.security.SecurityPolicyManagementService;
import com.ken.platform.identity.service.user.UserProfileService;
import com.ken.platform.identity.service.user.UserStatusService;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ManagementInputBoundaryTest {
  private static final String ID = "00000000-0000-0000-0000-000000000001";
  private MockMvc mvc;
  private Object[] services;

  @BeforeEach
  void setup() {
    var creation = mock(AccountCreationService.class);
    var lifecycle = mock(PlatformLifecycleService.class);
    var queries = mock(PlatformAccountQueryService.class);
    var users = mock(UserStatusService.class);
    var assignments = mock(RoleAssignmentService.class);
    var permissions = mock(RolePermissionService.class);
    var roles = mock(RoleManagementService.class);
    var profiles = mock(UserProfileService.class);
    var clients = mock(ClientManagementService.class);
    var own = mock(OwnAccountService.class);
    var policies = mock(SecurityPolicyManagementService.class);
    services =
        new Object[] {
          creation,
          lifecycle,
          queries,
          users,
          assignments,
          permissions,
          roles,
          profiles,
          clients,
          own,
          policies
        };
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AccountManagementController(creation, lifecycle, queries),
                new TenantManagementController(users, assignments, permissions, roles, profiles),
                new PlatformClientController(clients),
                new OwnAccountController(own),
                new SecurityManagementController(policies))
            .setHandlerExceptionResolvers(new ServletApiErrors())
            .build();
  }

  static Stream<Arguments> invalidVersions() {
    return Stream.of(
            new String[] {"PUT", "/platform/admins/" + ID, "version"},
            new String[] {"PUT", "/platform/tenants/" + ID, "version"},
            new String[] {"PUT", "/users/" + ID, "version"},
            new String[] {"PUT", "/users/" + ID + "/profile", "version"},
            new String[] {"PUT", "/users/" + ID + "/roles", "user_version"},
            new String[] {"PUT", "/roles/" + ID, "version"},
            new String[] {"DELETE", "/roles/" + ID, "version"},
            new String[] {"POST", "/roles/" + ID + ":copy", "role_version"},
            new String[] {"PUT", "/platform/oauth-clients/" + ID, "version"},
            new String[] {"POST", "/platform/oauth-clients/" + ID + "/secret:rotate", "version"},
            new String[] {"PUT", "/me/profile", "version"},
            new String[] {"PUT", "/platform/security-policy", "version"})
        .flatMap(
            row ->
                Stream.of("{}", "{\"" + row[2] + "\":-1}")
                    .map(body -> Arguments.of(row[0], row[1], body)));
  }

  @ParameterizedTest
  @MethodSource("invalidVersions")
  void missingOrNegativeVersionIsRejectedBeforeUseCase(String method, String path, String body)
      throws Exception {
    mvc.perform(
            request(HttpMethod.valueOf(method), "/api/v1/identity" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    verifyNoInteractions(services);
  }

  static Stream<Arguments> invalidIdentifiers() {
    return Stream.of(
            "/users/bad-id",
            "/roles/bad-id",
            "/platform/admins/bad-id",
            "/platform/tenants/bad-id",
            "/platform/oauth-clients/bad-id")
        .map(path -> Arguments.of(path));
  }

  @ParameterizedTest
  @MethodSource("invalidIdentifiers")
  void malformedUuidIsRejectedByHttpBinding(String path) throws Exception {
    mvc.perform(
            request(HttpMethod.PUT, "/api/v1/identity" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(services);
  }

  @org.junit.jupiter.api.Test
  void copyAlsoChecksCatalogVersionAtBoundary() throws Exception {
    mvc.perform(
            request(HttpMethod.POST, "/api/v1/identity/roles/" + ID + ":copy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role_version\":0,\"catalog_version\":-1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    verifyNoInteractions(services);
  }
}
