package com.example.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.webmvc.ui.SwaggerResourceResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

class SwaggerUiResourceTest {
  @Test
  void springdocResolvesUpdatedBundleWithPatchedSanitizer() throws Exception {
    var handler = new ResourceHttpRequestHandler();
    handler.setServletContext(new MockServletContext());
    handler.setLocations(List.of(new ClassPathResource("META-INF/resources/webjars/")));
    handler.setResourceResolvers(
        List.of(
            new SwaggerResourceResolver(new SwaggerUiConfigProperties()),
            new PathResourceResolver()));
    handler.afterPropertiesSet();
    var request = new MockHttpServletRequest("GET", "/swagger-ui/swagger-ui-bundle.js");
    request.setAttribute(
        HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "swagger-ui/swagger-ui-bundle.js");
    var response = new MockHttpServletResponse();

    handler.handleRequest(request, response);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString())
        .withFailMessage("Swagger UI bundle must include the patched DOMPurify 3.4.16")
        .contains("DOMPurify.version=\"3.4.16\"");
  }
}
