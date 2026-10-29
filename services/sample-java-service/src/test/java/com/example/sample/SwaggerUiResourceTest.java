package com.example.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
  @ParameterizedTest
  @CsvSource({
    "swagger-ui-bundle.js,e8ec45d876a2164779f4d3747fa84451b09517ec",
    "swagger-ui-es-bundle.js,a150f3495b6837ee7695cc4bdd11eb1eb76caa67"
  })
  void springdocResolvesReviewedBundleWithBoundedSanitizer(String file, String sha1)
      throws Exception {
    var handler = new ResourceHttpRequestHandler();
    handler.setServletContext(new MockServletContext());
    handler.setLocations(List.of(new ClassPathResource("META-INF/resources/webjars/")));
    handler.setResourceResolvers(
        List.of(
            new SwaggerResourceResolver(new SwaggerUiConfigProperties()),
            new PathResourceResolver()));
    handler.afterPropertiesSet();
    var request = new MockHttpServletRequest("GET", "/swagger-ui/" + file);
    request.setAttribute(
        HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "swagger-ui/" + file);
    var response = new MockHttpServletResponse();

    handler.handleRequest(request, response);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString())
        .withFailMessage("Swagger UI bundle must match the reviewed DOMPurify 3.4.16 artifact")
        .contains("DOMPurify.version=\"3.4.16\"");
    assertThat(
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-1").digest(response.getContentAsByteArray())))
        .as("The CVE applicability review is bound to these exact shipped bytes")
        .isEqualTo(sha1);
    var bundle = response.getContentAsString();
    int start = bundle.indexOf("function sanitizer(");
    var sanitizer =
        bundle.substring(start, bundle.indexOf("sanitizer.hasWarnedAboutDeprecation=!1", start));
    assertThat(sanitizer)
        .contains(
            ".sanitize(s,{ADD_ATTR:[\"target\"],FORBID_TAGS:[\"style\",\"form\"],ALLOW_DATA_ATTR:i,FORBID_ATTR:a})")
        .doesNotContain("IN_PLACE", "SAFE_FOR_TEMPLATES", "RETURN_DOM", "PARSER_MEDIA_TYPE");
    assertThat(bundle).contains("if(\"string\"!=typeof s)return null");
  }
}
