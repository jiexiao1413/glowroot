/*
 * Copyright 2012-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.collector;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.State;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.Static;
import org.glowroot.trace.model.Profile;
import org.glowroot.trace.model.ProfileNode;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class ProfileCharSourceCreator {

    private static final Logger logger = LoggerFactory.getLogger(ProfileCharSourceCreator.class);
    private static final JsonFactory jsonFactory = new JsonFactory();

    private ProfileCharSourceCreator() {}

    @Nullable
    public static CharSource createProfileCharSource(@Nullable Profile profile) {
        if (profile == null) {
            return null;
        }
        synchronized (profile.getLock()) {
            String profileJson = createProfileJson(profile.getSyntheticRootNode());
            if (profileJson == null) {
                return null;
            }
            return CharSource.wrap(profileJson);
        }
    }

    @Nullable
    static String createProfileJson(ProfileNode syntheticRootNode) {
        ProfileNode rootNode;
        if (syntheticRootNode.getChildNodes().isEmpty()) {
            return null;
        } else if (syntheticRootNode.getChildNodes().size() == 1) {
            // strip off synthetic root node since only one real root node
            rootNode = syntheticRootNode.getChildNodes().get(0);
        } else {
            rootNode = syntheticRootNode;
        }
        // need to convert profile into bytes entirely inside of the above lock (no lazy CharSource)
        StringWriter sw = new StringWriter(32768);
        try {
            new ProfileWriter(rootNode, sw).write();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        return sw.toString();
    }

    private static class ProfileWriter {

        private final List<Object> toVisit;
        private final JsonGenerator jg;
        private final List<String> traceMetricStack = Lists.newArrayList();

        private ProfileWriter(ProfileNode rootNode, Writer writer)
                throws IOException {
            this.toVisit = Lists.newArrayList((Object) rootNode);
            jg = jsonFactory.createGenerator(writer);
        }

        private void write() throws IOException {
            while (!toVisit.isEmpty()) {
                writeNext();
            }
            jg.close();
        }

        private void writeNext() throws IOException {
            Object curr = toVisit.remove(toVisit.size() - 1);
            if (curr instanceof ProfileNode) {
                ProfileNode currNode = (ProfileNode) curr;
                jg.writeStartObject();
                toVisit.add(JsonGeneratorOp.END_OBJECT);
                StackTraceElement stackTraceElement = currNode.getStackTraceElement();
                if (stackTraceElement == null) {
                    jg.writeStringField("stackTraceElement", "<multiple root nodes>");
                    jg.writeNumberField("sampleCount", currNode.getSampleCount());
                } else {
                    writeStackTraceElement(stackTraceElement, currNode);
                }
                List<ProfileNode> childNodes = currNode.getChildNodes();
                if (!childNodes.isEmpty()) {
                    jg.writeArrayFieldStart("childNodes");
                    toVisit.add(JsonGeneratorOp.END_ARRAY);
                    toVisit.addAll(Lists.reverse(childNodes));
                }
            } else if (curr == JsonGeneratorOp.END_ARRAY) {
                jg.writeEndArray();
            } else if (curr == JsonGeneratorOp.END_OBJECT) {
                jg.writeEndObject();
            } else if (curr == JsonGeneratorOp.POP_METRIC_NAME) {
                traceMetricStack.remove(traceMetricStack.size() - 1);
            }
        }

        private void writeStackTraceElement(StackTraceElement stackTraceElement,
                ProfileNode currNode) throws IOException {
            jg.writeStringField("stackTraceElement", stackTraceElement.toString());
            List<String> currTraceMetrics = currNode.getTraceMetrics();
            for (String currTraceMetric : currTraceMetrics) {
                if (traceMetricStack.isEmpty() || !currTraceMetric.equals(
                        traceMetricStack.get(traceMetricStack.size() - 1))) {
                    // filter out successive duplicates which are common from weaving groups
                    // of overloaded methods
                    traceMetricStack.add(currTraceMetric);
                    toVisit.add(JsonGeneratorOp.POP_METRIC_NAME);
                }
            }
            jg.writeNumberField("sampleCount", currNode.getSampleCount());
            State leafThreadState = currNode.getLeafThreadState();
            if (leafThreadState != null) {
                writeLeaf(leafThreadState);
            }
        }

        private void writeLeaf(State leafThreadState) throws IOException {
            jg.writeStringField("leafThreadState", leafThreadState.name());
            jg.writeArrayFieldStart("traceMetrics");
            for (String traceMetric : traceMetricStack) {
                jg.writeString(traceMetric);
            }
            jg.writeEndArray();
        }

        private static enum JsonGeneratorOp {
            END_OBJECT, END_ARRAY, POP_METRIC_NAME
        }
    }
}