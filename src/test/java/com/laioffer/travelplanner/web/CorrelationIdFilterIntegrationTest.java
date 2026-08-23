package com.laioffer.travelplanner.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterIntegrationTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void preservesAValidCallerCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cities");
        request.addHeader(CorrelationIdFilter.HEADER, "client-request_42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-request_42");
    }

    @Test
    void replacesUnsafeCorrelationIdInsteadOfReflectingIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cities");
        request.addHeader(CorrelationIdFilter.HEADER, "bad value forged");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
