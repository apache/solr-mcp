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

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint asserting that a value is a URL that SolrJ can
 * actually connect to.
 *
 * <p>
 * A value satisfies this constraint when it is an <em>absolute</em> URL whose
 * scheme is {@code http} or {@code https} and which carries a non-empty host.
 * That is precisely the set of URLs
 * {@link org.apache.solr.client.solrj.impl.HttpJdkSolrClient} can talk to, so
 * anything rejected here would have failed later anyway — at first request
 * rather than at startup, and with a far less actionable message.
 *
 * <p>
 * <strong>Why this is needed alongside {@code @NotBlank}:</strong>
 *
 * <p>
 * {@code @NotBlank} rejects only {@code null}, {@code ""} and all-whitespace
 * values. It happily accepts {@code solr.url=localhost:8983} — a common
 * misconfiguration, since omitting the scheme looks harmless.
 * {@link java.net.URI} parses that string as <em>scheme</em> {@code localhost}
 * with a {@code null} host, and {@link SolrConfig} normalizes it by pure string
 * concatenation into {@code localhost:8983/solr/} without ever noticing.
 *
 * <p>
 * <strong>Accepted:</strong>
 *
 * <ul>
 * <li>{@code http://localhost:8983}
 * <li>{@code http://localhost:8983/solr/}
 * <li>{@code https://solr.internal:8983/custom/solr/}
 * </ul>
 *
 * <p>
 * <strong>Rejected:</strong>
 *
 * <ul>
 * <li>{@code localhost:8983} — parses as scheme {@code localhost}, no host
 * <li>{@code ftp://solr.example.com/solr} — SolrJ cannot speak {@code ftp}
 * <li>{@code /solr} — not absolute
 * <li>{@code not a url} — unparseable
 * </ul>
 *
 * @see SolrUrlValidator
 * @see SolrConfigurationProperties
 */
@Documented
@Constraint(validatedBy = SolrUrlValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR,
		ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SolrUrl {

	/**
	 * {@return the validation message rendered when the value is not a usable Solr
	 * URL}
	 */
	String message() default "must be an absolute http or https URL including a host, for example http://localhost:8983/solr/";

	/** {@return the validation groups this constraint belongs to} */
	Class<?>[] groups() default {};

	/** {@return the payload associated with this constraint} */
	Class<? extends Payload>[] payload() default {};
}
