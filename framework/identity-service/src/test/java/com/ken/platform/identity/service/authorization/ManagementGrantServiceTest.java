package com.ken.platform.identity.service.authorization;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.identity.controller.management.TenantManagementController;
import com.ken.platform.identity.controller.management.TenantManagementQueryController;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/** 退役委派入口的合同测试；历史数据不删除，运行组件不能再加载旧用例。 */
class ManagementGrantServiceTest {
  @Test
  void retiredUseCasesAreAbsentFromApplicationClasspath() {
    for (String name :
        List.of(
            "ManagementGrantService",
            "ManagementDelegationAuthorization",
            "AuthorizationSimulationService"))
      assertThatThrownBy(
              () -> Class.forName("com.ken.platform.identity.service.authorization." + name))
          .isInstanceOf(ClassNotFoundException.class);
  }

  @Test
  void removedCommandsCannotReappearThroughManagementControllers() {
    for (Class<?> controller :
        List.of(TenantManagementController.class, TenantManagementQueryController.class))
      for (var method : controller.getDeclaredMethods()) {
        var get = method.getAnnotation(GetMapping.class);
        var put = method.getAnnotation(PutMapping.class);
        var post = method.getAnnotation(PostMapping.class);
        if (get != null)
          assertThat(Arrays.asList(get.value())).doesNotContain("/management-grants");
        if (put != null)
          assertThat(Arrays.asList(put.value())).doesNotContain("/management-grants");
        if (post != null)
          assertThat(Arrays.asList(post.value())).doesNotContain("/authorization-evaluations");
      }
  }

  @Test
  void obsoleteDelegationVersionsAreRejectedRatherThanSilentlyIgnored() {
    assertThatThrownBy(
            () ->
                new TenantManagementController.AssignmentCommand(0L, List.of())
                    .rejectUnknownField("management_grant_versions", java.util.Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TenantManagementController.PermissionCommand(0L, 0L, List.of())
                    .rejectUnknownField("management_grant_version", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
