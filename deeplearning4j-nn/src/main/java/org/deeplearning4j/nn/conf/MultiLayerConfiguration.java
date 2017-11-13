/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */



package org.deeplearning4j.nn.conf;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.api.OptimizationConfig;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.conf.memory.LayerMemoryReport;
import org.deeplearning4j.nn.conf.memory.MemoryReport;
import org.deeplearning4j.nn.conf.memory.NetworkMemoryReport;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.lossfunctions.impl.LossBinaryXENT;
import org.nd4j.linalg.lossfunctions.impl.LossMCXENT;
import org.nd4j.linalg.lossfunctions.impl.LossMSE;
import org.nd4j.linalg.lossfunctions.impl.LossNegativeLogLikelihood;
import org.nd4j.shade.jackson.databind.JsonNode;
import org.nd4j.shade.jackson.databind.ObjectMapper;
import org.nd4j.shade.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

/**
 * Configuration for a multi layer network
 *
 * @author Adam Gibson
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
@Slf4j
public class MultiLayerConfiguration implements OptimizationConfig, Serializable, Cloneable {

    protected List<Layer> confs;
    protected boolean pretrain = false;
    protected boolean backprop = true;
    protected BackpropType backpropType = BackpropType.Standard;
    protected int tbpttFwdLength = 20;
    protected int tbpttBackLength = 20;

    @Getter
    @Setter
    protected WorkspaceMode trainingWorkspaceMode;

    @Getter
    @Setter
    protected WorkspaceMode inferenceWorkspaceMode;

    @Getter
    @Setter
    protected CacheMode cacheMode;

    //Counter for the number of parameter updates so far
    // This is important for learning rate schedules, for example, and is stored here to ensure it is persisted
    // for Spark and model serialization
    protected int iterationCount = 0;

    //Counter for the number of epochs completed so far. Used for per-epoch schedules
    protected int epochCount = 0;

    //New fields, previously on defaultconfiguration, required for optimization etc:
    protected long seed;
    protected boolean miniBatch = true;
    protected boolean minimize = true;
    protected OptimizationAlgorithm optimizationAlgo = OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT;
    protected org.deeplearning4j.nn.conf.stepfunctions.StepFunction stepFunction = new org.deeplearning4j.nn.conf.stepfunctions.DefaultStepFunction();
    protected int maxNumLineSearchIterations = 5;

    /**
     *
     * @return  JSON representation of NN configuration
     */
    public String toYaml() {
        ObjectMapper mapper = NeuralNetConfiguration.mapperYaml();
        synchronized (mapper) {
            try {
                return mapper.writeValueAsString(this);
            } catch (org.nd4j.shade.jackson.core.JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Create a neural net configuration from json
     * @param json the neural net configuration from json
     * @return {@link MultiLayerConfiguration}
     */
    public static MultiLayerConfiguration fromYaml(String json) {
        ObjectMapper mapper = NeuralNetConfiguration.mapperYaml();
        try {
            return mapper.readValue(json, MultiLayerConfiguration.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }



    /**
     *
     * @return  JSON representation of NN configuration
     */
    public String toJson() {
        ObjectMapper mapper = NeuralNetConfiguration.mapper();
        synchronized (mapper) {
            //JSON mappers are supposed to be thread safe: however, in practice they seem to miss fields occasionally
            //when writeValueAsString is used by multiple threads. This results in invalid JSON. See issue #3243
            try {
                return mapper.writeValueAsString(this);
            } catch (org.nd4j.shade.jackson.core.JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Create a neural net configuration from json
     * @param json the neural net configuration from json
     * @return {@link MultiLayerConfiguration}
     */
    public static MultiLayerConfiguration fromJson(String json) {
        MultiLayerConfiguration conf;
        ObjectMapper mapper = NeuralNetConfiguration.mapper();
        try {
            conf = mapper.readValue(json, MultiLayerConfiguration.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        //To maintain backward compatibility after loss function refactoring (configs generated with v0.5.0 or earlier)
        // Previously: enumeration used for loss functions. Now: use classes
        // IN the past, could have only been an OutputLayer or RnnOutputLayer using these enums
        int layerCount = 0;
        JsonNode confs = null;
        for (Layer l : conf.getConfs()) {
            if (l instanceof BaseOutputLayer && ((BaseOutputLayer) l).getLossFn() == null) {
                //lossFn field null -> may be an old config format, with lossFunction field being for the enum
                //if so, try walking the JSON graph to extract out the appropriate enum value

                BaseOutputLayer ol = (BaseOutputLayer) l;
                try {
                    JsonNode jsonNode = mapper.readTree(json);
                    if (confs == null) {
                        confs = jsonNode.get("confs");
                    }
                    if (confs instanceof ArrayNode) {
                        ArrayNode layerConfs = (ArrayNode) confs;
                        JsonNode outputLayerNNCNode = layerConfs.get(layerCount);
                        if (outputLayerNNCNode == null)
                            return conf; //Should never happen...
                        JsonNode outputLayerNode = outputLayerNNCNode.get("layer");

                        JsonNode lossFunctionNode = null;
                        if (outputLayerNode.has("output")) {
                            lossFunctionNode = outputLayerNode.get("output").get("lossFunction");
                        } else if (outputLayerNode.has("rnnoutput")) {
                            lossFunctionNode = outputLayerNode.get("rnnoutput").get("lossFunction");
                        }

                        if (lossFunctionNode != null) {
                            String lossFunctionEnumStr = lossFunctionNode.asText();
                            LossFunctions.LossFunction lossFunction = null;
                            try {
                                lossFunction = LossFunctions.LossFunction.valueOf(lossFunctionEnumStr);
                            } catch (Exception e) {
                                log.warn("OutputLayer with null LossFunction or pre-0.6.0 loss function configuration detected: could not parse JSON",
                                                e);
                            }

                            if (lossFunction != null) {
                                switch (lossFunction) {
                                    case MSE:
                                        ol.setLossFn(new LossMSE());
                                        break;
                                    case XENT:
                                        ol.setLossFn(new LossBinaryXENT());
                                        break;
                                    case NEGATIVELOGLIKELIHOOD:
                                        ol.setLossFn(new LossNegativeLogLikelihood());
                                        break;
                                    case MCXENT:
                                        ol.setLossFn(new LossMCXENT());
                                        break;

                                    //Remaining: TODO
                                    case EXPLL:
                                    case RMSE_XENT:
                                    case SQUARED_LOSS:
                                    case RECONSTRUCTION_CROSSENTROPY:
                                    case CUSTOM:
                                    default:
                                        log.warn("OutputLayer with null LossFunction or pre-0.6.0 loss function configuration detected: could not set loss function for {}",
                                                        lossFunction);
                                        break;
                                }
                            }
                        }

                    } else {
                        log.warn("OutputLayer with null LossFunction or pre-0.6.0 loss function configuration detected: could not parse JSON: layer 'confs' field is not an ArrayNode (is: {})",
                                        (confs != null ? confs.getClass() : null));
                    }
                } catch (IOException e) {
                    log.warn("OutputLayer with null LossFunction or pre-0.6.0 loss function configuration detected: could not parse JSON",
                                    e);
                    break;
                }
            }

            //Also, pre 0.7.2: activation functions were Strings ("activationFunction" field), not classes ("activationFn")
            //Try to load the old format if necessary, and create the appropriate IActivation instance
            if ((l instanceof BaseLayer) && ((BaseLayer) l).getActivationFn() == null) {
                try {
                    JsonNode jsonNode = mapper.readTree(json);
                    if (confs == null) {
                        confs = jsonNode.get("confs");
                    }
                    if (confs instanceof ArrayNode) {
                        ArrayNode layerConfs = (ArrayNode) confs;
                        JsonNode outputLayerNNCNode = layerConfs.get(layerCount);
                        if (outputLayerNNCNode == null)
                            return conf; //Should never happen...
                        JsonNode layerWrapperNode = outputLayerNNCNode.get("layer");

                        if (layerWrapperNode == null || layerWrapperNode.size() != 1) {
                            continue;
                        }

                        JsonNode layerNode = layerWrapperNode.elements().next();
                        JsonNode activationFunction = layerNode.get("activationFunction"); //Should only have 1 element: "dense", "output", etc

                        if (activationFunction != null) {
                            IActivation ia = Activation.fromString(activationFunction.asText()).getActivationFunction();
                            ((BaseLayer) l).setActivationFn(ia);
                        }
                    }

                } catch (IOException e) {
                    log.warn("Layer with null ActivationFn field or pre-0.7.2 activation function detected: could not parse JSON",
                                    e);
                }
            }

            layerCount++;
        }
        return conf;
    }

    @Override
    public String toString() {
        return toJson();
    }

    public Layer getConf(int i) {
        return confs.get(i);
    }

    @Override
    public MultiLayerConfiguration clone() {
        try {
            MultiLayerConfiguration clone = (MultiLayerConfiguration) super.clone();

            if (clone.confs != null) {
                List<Layer> list = new ArrayList<>();
                for (Layer conf : clone.confs) {
                    list.add(conf.clone());
                }
                clone.confs = list;
            }

            clone.inferenceWorkspaceMode = this.inferenceWorkspaceMode;
            clone.trainingWorkspaceMode = this.trainingWorkspaceMode;
            clone.cacheMode = this.cacheMode;

            clone.seed = seed;
            clone.miniBatch = miniBatch;
            clone.minimize = minimize;
            clone.optimizationAlgo = optimizationAlgo;
            clone.stepFunction = stepFunction;

            return clone;

        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get a {@link MemoryReport} for the given MultiLayerConfiguration. This is used to estimate the
     * memory requirements for the given network configuration and input
     *
     * @param inputType Input types for the network
     * @return Memory report for the network
     */
    public NetworkMemoryReport getMemoryReport(InputType inputType) {

        Map<String, MemoryReport> memoryReportMap = new LinkedHashMap<>();
        int nLayers = confs.size();
        for (int i = 0; i < nLayers; i++) {
            String layerName = confs.get(i).getLayerName();
            if (layerName == null) {
                layerName = String.valueOf(i);
            }

            LayerMemoryReport report = confs.get(i).getMemoryReport(inputType);
            memoryReportMap.put(layerName, report);

            inputType = confs.get(i).getOutputType(i, inputType)[0];
        }

        return new NetworkMemoryReport(memoryReportMap, MultiLayerConfiguration.class, "MultiLayerNetwork", inputType);
    }

    @Data
    public static class Builder {

        protected GlobalConfiguration globalConfiguration;
        protected List<Layer> confs = new ArrayList<>();
        protected Map<Integer, InputPreProcessor> inputPreProcessors = new HashMap<>();
        protected boolean pretrain = false;
        protected boolean backprop = true;
        protected BackpropType backpropType = BackpropType.Standard;
        protected int tbpttFwdLength = 20;
        protected int tbpttBackLength = 20;
        protected InputType inputType;

        protected WorkspaceMode trainingWorkspaceMode = WorkspaceMode.NONE;
        protected WorkspaceMode inferenceWorkspaceMode = WorkspaceMode.SEPARATE;
        protected CacheMode cacheMode = CacheMode.NONE;

        public Builder globalConfiguration(GlobalConfiguration globalConfiguration){
            this.globalConfiguration = globalConfiguration;
            return this;
        }

        /**
         * Specify the processors.
         * These are used at each layer for doing things like normalization and
         * shaping of input.
         * @param processor what to use to preProcess the data.
         * @return builder pattern
         * @deprecated Now: set input preprocessors on layers configurations/builders - {@link Layer.Builder#preProcessor(InputPreProcessor)}
         */
        @Deprecated
        public Builder inputPreProcessor(Integer layer, InputPreProcessor processor) {
            inputPreProcessors.put(layer, processor);
            return this;
        }

        /**
         * @deprecated Now: set input preprocessors on layers configurations/builders - {@link Layer.Builder#preProcessor(InputPreProcessor)}
         */
        @Deprecated
        public Builder inputPreProcessors(Map<Integer, InputPreProcessor> processors) {
            this.inputPreProcessors = processors;
            return this;
        }

        /**
         * Whether to do back prop or not
         * @param backprop whether to do back prop or not
         * @return
         */
        public Builder backprop(boolean backprop) {
            this.backprop = backprop;
            return this;
        }

        /**
         * This method defines Workspace mode being used during training:
         * NONE: workspace won't be used
         * SINGLE: one workspace will be used during whole iteration loop
         * SEPARATE: separate workspaces will be used for feedforward and backprop iteration loops
         *
         * @param workspaceMode
         * @return
         */
        public Builder trainingWorkspaceMode(@NonNull WorkspaceMode workspaceMode) {
            this.trainingWorkspaceMode = workspaceMode;
            return this;
        }

        /**
         * This method defines Workspace mode being used during inference:
         * NONE: workspace won't be used
         * SINGLE: one workspace will be used during whole iteration loop
         * SEPARATE: separate workspaces will be used for feedforward and backprop iteration loops
         *
         * @param workspaceMode
         * @return
         */
        public Builder inferenceWorkspaceMode(@NonNull WorkspaceMode workspaceMode) {
            this.inferenceWorkspaceMode = workspaceMode;
            return this;
        }

        /**
         * This method defines how/if preOutput cache is handled:
         * NONE: cache disabled (default value)
         * HOST: Host memory will be used
         * DEVICE: GPU memory will be used (on CPU backends effect will be the same as for HOST)
         *
         * @param cacheMode
         * @return
         */
        public Builder cacheMode(@NonNull CacheMode cacheMode) {
            this.cacheMode = cacheMode;
            return this;
        }

        /**The type of backprop. Default setting is used for most networks (MLP, CNN etc),
         * but optionally truncated BPTT can be used for training recurrent neural networks.
         * If using TruncatedBPTT make sure you set both tBPTTForwardLength() and tBPTTBackwardLength()
         */
        public Builder backpropType(BackpropType type) {
            this.backpropType = type;
            return this;
        }

        /**When doing truncated BPTT: how many steps should we do?<br>
         * Only applicable when doing backpropType(BackpropType.TruncatedBPTT)<br>
         * See: http://www.cs.utoronto.ca/~ilya/pubs/ilya_sutskever_phd_thesis.pdf
         * @param bpttLength length > 0
         */
        public Builder tBPTTLength(int bpttLength) {
            tBPTTForwardLength(bpttLength);
            return tBPTTBackwardLength(bpttLength);
        }

        /**When doing truncated BPTT: how many steps of forward pass should we do
         * before doing (truncated) backprop?<br>
         * Only applicable when doing backpropType(BackpropType.TruncatedBPTT)<br>
         * Typically tBPTTForwardLength parameter is same as the tBPTTBackwardLength parameter,
         * but may be larger than it in some circumstances (but never smaller)<br>
         * Ideally your training data time series length should be divisible by this
         * This is the k1 parameter on pg23 of
         * http://www.cs.utoronto.ca/~ilya/pubs/ilya_sutskever_phd_thesis.pdf
         * @param forwardLength Forward length > 0, >= backwardLength
         */
        public Builder tBPTTForwardLength(int forwardLength) {
            this.tbpttFwdLength = forwardLength;
            return this;
        }

        /**When doing truncated BPTT: how many steps of backward should we do?<br>
         * Only applicable when doing backpropType(BackpropType.TruncatedBPTT)<br>
         * This is the k2 parameter on pg23 of
         * http://www.cs.utoronto.ca/~ilya/pubs/ilya_sutskever_phd_thesis.pdf
         * @param backwardLength <= forwardLength
         */
        public Builder tBPTTBackwardLength(int backwardLength) {
            this.tbpttBackLength = backwardLength;
            return this;
        }

        /**
         * Whether to do pre train or not
         * @param pretrain whether to do pre train or not
         * @return builder pattern
         */
        public Builder pretrain(boolean pretrain) {
            this.pretrain = pretrain;
            return this;
        }

        public Builder confs(List<Layer> confs) {
            this.confs = confs;
            return this;
        }

        public Builder setInputType(InputType inputType) {
            this.inputType = inputType;
            return this;
        }

        public MultiLayerConfiguration build() {
            if (inputType == null && inputPreProcessors.get(0) == null) {
                //User hasn't set the InputType. Sometimes we can infer it...
                // For example, Dense/RNN layers, where preprocessor isn't set -> user is *probably* going to feed in
                // standard feedforward or RNN data
                //This isn't the most elegant implementation, but should avoid breaking backward compatibility here
                //Can't infer InputType for CNN layers, however (don't know image dimensions/depth)
                Layer firstLayer = confs.get(0);
                if (firstLayer instanceof BaseRecurrentLayer) {
                    BaseRecurrentLayer brl = (BaseRecurrentLayer) firstLayer;
                    int nIn = brl.getNIn();
                    if (nIn > 0) {
                        inputType = InputType.recurrent(nIn);
                    }
                } else if (firstLayer instanceof DenseLayer || firstLayer instanceof EmbeddingLayer
                                || firstLayer instanceof OutputLayer) {
                    //Can't just use "instanceof FeedForwardLayer" here. ConvolutionLayer is also a FeedForwardLayer
                    FeedForwardLayer ffl = (FeedForwardLayer) firstLayer;
                    int nIn = ffl.getNIn();
                    if (nIn > 0) {
                        inputType = InputType.feedForward(nIn);
                    }
                }
            }

            //Copy the global configuration to the layers. Has to happen before input type inference
            if(globalConfiguration != null) {
                for (Layer l : confs) {
                    l.applyGlobalConfiguration(globalConfiguration);
                }
            }

            //Add preprocessors and set nIns, if InputType has been set
            // Builder.inputType field can be set in 1 of 4 ways:
            // 1. User calls setInputType directly
            // 2. Via ConvolutionLayerSetup -> internally calls setInputType(InputType.convolutional(...))
            // 3. Via the above code: i.e., assume input is as expected  by the RNN or dense layer -> sets the inputType field
            if (inputType != null) {
                InputType currentInputType = inputType;
                for (int i = 0; i < confs.size(); i++) {
                    Layer l = confs.get(i);
                    if (inputPreProcessors.get(i) == null) {
                        //Don't override preprocessor setting, but set preprocessor if required...
                        InputPreProcessor inputPreProcessor = l.getPreProcessorForInputType(currentInputType);
                        if (inputPreProcessor != null) {
                            l.setPreProcessor(inputPreProcessor);
                            l.setNIn(new InputType[]{currentInputType}, false); //Don't override the nIn setting, if it's manually set by the user
                        } else {
                            l.setNIn(new InputType[]{currentInputType}, false); //Don't override the nIn setting, if it's manually set by the user
                        }
                    } else {
                        InputPreProcessor inputPreProcessor = inputPreProcessors.get(i);
                        if (inputPreProcessor != null) {
                            l.setPreProcessor(inputPreProcessor);   //Set on layer, from legacy map
                        }
                        l.setNIn(new InputType[]{currentInputType}, false); //Don't override the nIn setting, if it's manually set by the user
                    }

                    currentInputType = l.getOutputType(i, currentInputType)[0];
                }
            } else if(inputPreProcessors.size() > 0){
                //Set preprocesors from legacy map config...
                for(Map.Entry<Integer,InputPreProcessor> e : inputPreProcessors.entrySet()){
                    confs.get(e.getKey()).setPreProcessor(e.getValue());
                }
            }

            MultiLayerConfiguration conf = new MultiLayerConfiguration();
            conf.confs = this.confs;
            conf.pretrain = pretrain;
            conf.backprop = backprop;
            conf.backpropType = backpropType;
            conf.tbpttFwdLength = tbpttFwdLength;
            conf.tbpttBackLength = tbpttBackLength;
            conf.trainingWorkspaceMode = trainingWorkspaceMode;
            conf.inferenceWorkspaceMode = inferenceWorkspaceMode;
            conf.cacheMode = cacheMode;

            //And apply global configuration to ComputationGraphConfiguration:
            GlobalConfiguration gc = globalConfiguration;
            conf.seed = gc.getSeed();
            conf.miniBatch = gc.getMiniBatch();
            conf.minimize = gc.getMinimize();
            conf.optimizationAlgo = gc.getOptimizationAlgo();
            conf.stepFunction = gc.getStepFunction();
            conf.maxNumLineSearchIterations = gc.getMaxNumLineSearchIterations();

            return conf;
        }


    }
}