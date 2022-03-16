/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.contrib.jmxmetrics


import groovy.jmx.GroovyMBean
import groovy.transform.PackageScope

import javax.management.openmbean.CompositeData
import java.util.logging.Logger

/**
 * A helper for easy instrument creation and updates based on an
 * {@link io.opentelemetry.contrib.jmxmetrics.MBeanHelper} attribute's value and passed {@link io.opentelemetry.contrib.jmxmetrics.OtelHelper}
 * instrument creator method pointer (e.g. &longCounter).
 *
 * Intended to be used via the script-bound `otel` {@link io.opentelemetry.contrib.jmxmetrics.OtelHelper} instance methods:
 *
 * def threadCount = otel.instrument(myThreadingMBeanHelper,
 *       "jvm.threads.count", "number of threads",
 *       "1", [
 *         "myLabel": { mbean -> mbean.name().getKeyProperty("myObjectNameProperty") },
 *         "myOtherLabel": { "myLabelValue" }
 *       ], "ThreadCount", otel.&longUpDownCounter)
 *
 * threadCount.update()
 *
 * If the underlying MBean(s) held by the MBeanHelper are
 * {@link CompositeData} instances, each key of their CompositeType's
 * keySet will be .-appended to the specified instrumentName and
 * updated for each respective value.
 */
class NGPExampleInstrumentHelper {
    private static final Logger logger = Logger.getLogger(NGPExampleInstrumentHelper.class.getName());

    private final OtelHelper otelHelper
    private final MBeanHelper mBeanHelper
    private final String instrumentName
    private final String description
    private final String unit
    private final Map<String, Map<String, Closure>> mBeanAttributes
    private final Map<String, Closure> labelFuncs

    /**
     * An InstrumentHelper provides the ability to easily create and update {@link io.opentelemetry.api.metrics.Instrument}
     * instances from an MBeanHelper's underlying {@link GroovyMBean} instances via an {@link io.opentelemetry.contrib.jmxmetrics.OtelHelper}'s instrument
     * method pointer.
     *
     * @param mBeanHelper - the single or multiple {@link GroovyMBean}-representing MBeanHelper from which to access attribute values
     * @param instrumentName - the resulting instruments' name to register.
     * @param description - the resulting instruments' description to register.
     * @param unit - the resulting instruments' unit to register.
     * @param labelFuncs - A {@link Map<String, Closure>} of label names and values to be determined by custom
     *        {@link GroovyMBean}-provided Closures: (e.g. [ "myLabelName" : { mbean -> "myLabelValue"} ]). The
     *        resulting Label instances will be used for each individual update.
     * @param attribute - The {@link GroovyMBean} attribute for which to use as the instrument value.
     * @param instrument - The {@link io.opentelemetry.api.metrics.Instrument}-producing {@link io.opentelemetry.contrib.jmxmetrics.OtelHelper} method pointer:
     *        (e.g. new OtelHelper().&doubleValueRecorder)
     */
    NGPExampleInstrumentHelper(OtelHelper otelHelper, MBeanHelper mBeanHelper, String instrumentName, String description, String unit, Map<String, Closure<?>> labelFuncs, Map<String, Map<String, Closure<?>>> MBeanAttributes) {
        // in this example we use the Otel Helper instead of any particular instrumentation closure
        // to be able to create the different types of metrics depending on field type
        this.otelHelper = otelHelper
        this.mBeanHelper = mBeanHelper
        this.instrumentName = instrumentName
        this.description = description
        this.unit = unit
        this.labelFuncs = labelFuncs
        this.mBeanAttributes = MBeanAttributes
    }

    void update() {
        // Tuples of the form (mbean, attribute, value)
        def values = mBeanHelper.getAttributes(mBeanAttributes.keySet())

        // If there are no tuples with non-null value, return early
        if (values.find {it.getV3() != null } == null) {
            logger.warning("No valid value(s) for ${instrumentName} - ${mBeanHelper}.${mBeanAttributes.keySet().join(",")}")
            return
        }

        // Observer instruments need to have a single updater set at build time, so pool all
        // update operations in a list of closures per instrument to be executed after all values
        // are established, potentially as a single updater.  This is done because a single MBeanHelper
        // can represent multiple MBeans (each with different values for an attribute) and the labelFuncs
        // will create multiple datapoints from the same instrument identifiers.
        def tupleToUpdates = [:] // tuple is of form (instrument, instrumentName, description, unit)

        values.each { collectedValue ->
            def mbean = collectedValue.getV1()
            def attribute = collectedValue.getV2()
            def value = collectedValue.getV3()
            if (value instanceof CompositeData) {
                value.getCompositeType().keySet().each { key ->
                    def val = value.get(key)
                    def updatedInstrumentName = "${instrumentName}.${key}"
                    def labels = getLabels(mbean, labelFuncs, mBeanAttributes[attribute])
                    // In this example we base on the value data type to recognize and create the particular metrics types
                    // by using a proper closure to record data
                    // but in fact it is up to you, for example in the real scenario you can use the value type and a key name
                    // as a condition to decide what metric type should be created
                    if (val instanceof Double) {
                        def tuple = new Tuple(otelHelper.&doubleValueCallback, updatedInstrumentName, description, unit)
                        if (!tupleToUpdates.containsKey(tuple)) {
                            tupleToUpdates[tuple] = []
                        }
                        tupleToUpdates[tuple].add(prepareUpdateClosure(otelHelper.&doubleValueCallback, val, labels))
                    } else if (val instanceof Long) {
                        def tuple = new Tuple(otelHelper.&longValueCallback, updatedInstrumentName, description, unit)
                        if (!tupleToUpdates.containsKey(tuple)) {
                            tupleToUpdates[tuple] = []
                        }
                        tupleToUpdates[tuple].add(prepareUpdateClosure(otelHelper.&longValueCallback, val, labels))
                    } else if (val instanceof Date) {
                        logger.warning("Date type is not supported")
                    } else if (val instanceof CompositeData) {
                        // nested composite type will be analyzed only if it's a well known custom type 'LongStat'
                        if (val.getCompositeType().getTypeName().equals("com.sabre.ngp.example.metrics.LongStat")) {
                            val.getCompositeType().keySet().each {
                                internalKey -> {
                                    def internalVal = val.get(internalKey)
                                    def internalUpdatedInstrumentName = "${updatedInstrumentName}.${internalKey}"
                                    if (internalVal instanceof Double) {
                                        def tuple = new Tuple(otelHelper.&doubleValueCallback, internalUpdatedInstrumentName, description, unit)
                                        if (!tupleToUpdates.containsKey(tuple)) {
                                            tupleToUpdates[tuple] = []
                                        }
                                        tupleToUpdates[tuple].add(prepareUpdateClosure(otelHelper.&doubleValueCallback, internalVal, labels))
                                    } else if (internalVal instanceof Long) {
                                        def tuple = new Tuple(otelHelper.&longValueCallback, internalUpdatedInstrumentName, description, unit)
                                        if (!tupleToUpdates.containsKey(tuple)) {
                                            tupleToUpdates[tuple] = []
                                        }
                                        tupleToUpdates[tuple].add(prepareUpdateClosure(otelHelper.&longValueCallback, internalVal, labels))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        tupleToUpdates.each {tuple, updateClosures ->
            def instrument = tuple.getAt(0)
            def instrumentName = tuple.getAt(1)
            def description = tuple.getAt(2)
            def unit = tuple.getAt(3)

            if (instrumentIsObserver(instrument)) {
                // Though the instrument updater is only set at build time,
                // our GroovyMetricEnvironment helpers ensure the updater
                // uses the zClosure specified here.
                instrument(instrumentName, description, unit, { result ->
                    updateClosures.each { update ->
                        update(result)
                    }
                })
            } else {
                def inst = instrument(instrumentName, description, unit)
                updateClosures.each {
                    it(inst)
                }
            }
        }
    }

    private static Map<String, String> getLabels(GroovyMBean mbean, Map<String, Closure> labelFuncs, Map<String, Closure> additionalLabels) {
        def labels = [:]
        labelFuncs.each { label, labelFunc ->
          labels[label] = labelFunc(mbean) as String
        }
        additionalLabels.each {label, labelFunc ->
            labels[label] = labelFunc(mbean) as String
        }
        return labels
    }

    private static Closure prepareUpdateClosure(inst, value, labels) {
        def labelMap = GroovyMetricEnvironment.mapToAttributes(labels)
        if (instrumentIsObserver(inst)) {
            return { result ->
                result.record(value, labelMap)
            }
        } else if (instrumentIsCounter(inst)) {
            return { i -> i.add(value, labelMap) }
        } else {
            return { i -> i.record(value, labelMap) }
        }
    }

    @PackageScope static boolean instrumentIsObserver(inst) {
        return [
            "doubleCounterCallback",
            "doubleUpDownCounterCallback",
            "longCounterCallback",
            "longUpDownCounterCallback",
            "doubleValueCallback",
            "longValueCallback"
        ].contains(inst.method)
    }

    @PackageScope static boolean instrumentIsCounter(inst) {
        return [
            "doubleCounter",
            "doubleUpDownCounter",
            "longCounter",
            "longUpDownCounter"
        ].contains(inst.method)
    }
}
