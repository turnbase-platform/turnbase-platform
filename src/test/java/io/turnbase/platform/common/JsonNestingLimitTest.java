package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code input-validation.md} § Limits: a body nested deeper than {@code StreamReadConstraints}
 * allows is {@code 400 validation-failed} — the limit survived the move to Jackson 3.
 */
class JsonNestingLimitTest {

  @RestController
  static class Echo {
    @PostMapping("/echo")
    String echo(@RequestBody JsonNode body) {
      return "ok";
    }
  }

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new Echo())
          .setMessageConverters(new JacksonJsonHttpMessageConverter(GoldenJson.mapper()))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  private static String nested(int depth) {
    return "[".repeat(depth) + "]".repeat(depth);
  }

  @Test
  void aBodyAtTheLimitIsParsed() throws Exception {
    mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content(nested(100)))
        .andExpect(status().isOk());
  }

  @Test
  void aBodyOneDeeperThanTheLimitIsAValidationFailure() throws Exception {
    var body =
        mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content(nested(101)))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("validation-failed");
  }
}
