/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.server.config;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Validates {@link SolrUrl} by parsing the candidate value with Spring's
 * {@link UriComponentsBuilder} and inspecting the resulting scheme and host.
 *
 * <p>
 * {@code UriComponentsBuilder} is used rather than {@link java.net.URL} because
 * it parses without performing any network or protocol-handler lookup, and it
 * exposes {@link UriComponents#getScheme()} and {@link UriComponents#getHost()}
 * as independent components — which is exactly the distinction that separates
 * {@code http://localhost:8983} from {@code localhost:8983}.
 *
 * @see SolrUrl
 */
public class SolrUrlValidator implements ConstraintValidator<SolrUrl, String> {

	private static final String HTTP_SCHEME = "http";
	private static final String HTTPS_SCHEME = "https";

	/** Default constructor used by the Bean Validation provider. */
	public SolrUrlValidator() {
	}

	/**
	 * Determines whether {@code value} is a URL SolrJ can connect to.
	 *
	 * <p>
	 * Null and blank values are reported as valid so that emptiness stays the
	 * concern of {@code @NotBlank}. Without this, an unset {@code solr.url} would
	 * surface two overlapping messages instead of one.
	 *
	 * @param value
	 *            the configured URL, or {@code null} when the property is unset
	 * @param context
	 *            the constraint validator context
	 * @return {@code true} if {@code value} satisfies the constraint
	 */
	@Override
	public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
		// Emptiness is @NotBlank's responsibility; reporting it here too would
		// yield two messages for a single mistake.
		if (!StringUtils.hasText(value)) {
			return true;
		}

		UriComponents uri;
		try {
			uri = UriComponentsBuilder.fromUriString(value).build();
		} catch (IllegalArgumentException ex) {
			// Not parseable as a URI at all (for example "not a url").
			return false;
		}

		// A missing scheme means the value is relative ("/solr"); a scheme that
		// is neither http nor https is one SolrJ's HTTP client cannot speak.
		String scheme = uri.getScheme();
		if (!HTTP_SCHEME.equals(scheme) && !HTTPS_SCHEME.equals(scheme)) {
			return false;
		}

		// "localhost:8983" parses as scheme "localhost" with no host and is
		// already excluded above, but "http://" reaches here with a null host.
		return StringUtils.hasText(uri.getHost());
	}
}
