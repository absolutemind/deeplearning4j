package org.nd4j.parameterserver.distributed.training.impl;

import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.parameterserver.distributed.logic.completion.FrameCompletionHandler;
import org.nd4j.parameterserver.distributed.logic.completion.RequestDescriptor;
import org.nd4j.parameterserver.distributed.logic.storage.WordVectorStorage;
import org.nd4j.parameterserver.distributed.messages.VoidAggregation;
import org.nd4j.parameterserver.distributed.messages.aggregations.DotAggregation;
import org.nd4j.parameterserver.distributed.messages.complete.FrameCompleteMessage;
import org.nd4j.parameterserver.distributed.messages.intercom.DistributedCbowDotMessage;
import org.nd4j.parameterserver.distributed.messages.requests.CbowRequestMessage;
import org.nd4j.parameterserver.distributed.training.BaseTrainer;
import org.nd4j.parameterserver.distributed.training.TrainingDriver;
import org.nd4j.parameterserver.distributed.training.chains.CbowChain;
import org.nd4j.parameterserver.distributed.training.chains.SkipGramChain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author raver119@gmail.com
 */
@Slf4j
public class CbowTrainer extends BaseTrainer<CbowRequestMessage> {
    private static final float HS_MAX_EXP = 6.0f;

    protected Map<RequestDescriptor, CbowChain> chains = new ConcurrentHashMap<>();
    protected AtomicLong cntRounds = new AtomicLong(0);



    @Override
    public void startTraining(CbowRequestMessage message) {
        CbowChain chain = new CbowChain(message);
        chain.addElement(message);

        chains.put(RequestDescriptor.createDescriptor(message.getOriginatorId(), message.getTaskId()), chain);


        DistributedCbowDotMessage dcdm = new DistributedCbowDotMessage(message.getTaskId(), message.getSyn0rows(), message.getSyn1rows(), message.getW1(), message.getCodes(), message.getCodes().length > 0, (short) message.getNegSamples(), (float) message.getAlpha());
        dcdm.setTargetId((short) - 1);
        dcdm.setOriginatorId(message.getOriginatorId());
        transport.sendMessage(dcdm);
    }

    @Override
    public void pickTraining(CbowRequestMessage message) {
        RequestDescriptor descriptor = RequestDescriptor.createDescriptor(message.getOriginatorId(), message.getTaskId());
        if (!chains.containsKey(descriptor)) {
            CbowChain chain = new CbowChain(message);
            chains.put(descriptor, chain);
        }
    }

    @Override
    public void aggregationFinished(VoidAggregation aggregation) {
        // we just pick DotAggregation here

        CbowChain chain = chains.get(RequestDescriptor.createDescriptor(aggregation.getOriginatorId(), aggregation.getTaskId()));
        if (chain == null) {
            throw new RuntimeException("sI_" + transport.getShardIndex() + " Unable to find chain for specified originatorId: ["+ aggregation.getOriginatorId()+"]; taskId: [" + aggregation.getTaskId() + "]");
        }

        chain.addElement((DotAggregation) aggregation);

        finishTraining(aggregation.getOriginatorId(), aggregation.getTaskId());
    }

    @Override
    public void finishTraining(long originatorId, long taskId) {
        RequestDescriptor chainDesc = RequestDescriptor.createDescriptor(originatorId, taskId);
        CbowChain chain = chains.get(chainDesc);

        if (chain == null)
            throw new RuntimeException("Unable to find chain for specified taskId: [" + taskId + "]");

        CbowRequestMessage cbr = chain.getCbowRequest();
        double alpha = cbr.getAlpha();

        //log.info("Executing SkipGram round on shard_{}; taskId: {}", transport.getShardIndex(), taskId);

        // TODO: We DON'T want this code being here
        // TODO: We DO want this algorithm to be native
        INDArray expTable = storage.getArray(WordVectorStorage.EXP_TABLE);
        INDArray dots = chain.getDotAggregation().getAccumulatedResult();

        INDArray syn0 = storage.getArray(WordVectorStorage.SYN_0);
        INDArray syn1 = storage.getArray(WordVectorStorage.SYN_1);
        INDArray syn1Neg = storage.getArray(WordVectorStorage.SYN_1_NEGATIVE);

        INDArray words = Nd4j.pullRows(storage.getArray(WordVectorStorage.SYN_0), 1, cbr.getSyn0rows(), 'c' );
        INDArray neue = words.mean(1);

        INDArray neu1e = Nd4j.create(syn1.columns());

        // probably applying HS part
        if (cbr.getCodes().length > 0) {

        }



        // we send back confirmation message only from Shard which received this message
        RequestDescriptor descriptor = RequestDescriptor.createDescriptor(chain.getOriginatorId(), chain.getFrameId());

        if (completionHandler.isTrackingFrame(descriptor)) {
            completionHandler.notifyFrame(chain.getOriginatorId(), chain.getFrameId(), chain.getTaskId());

            if (completionHandler.isCompleted(descriptor)) {
                FrameCompletionHandler.FrameDescriptor frameDescriptor = completionHandler.getCompletedFrameInfo(descriptor);


                // TODO: there is possible race condition here
                if (frameDescriptor != null) {
                    FrameCompleteMessage fcm = new FrameCompleteMessage(chain.getFrameId());
                    fcm.setOriginatorId(frameDescriptor.getFrameOriginatorId());
                    transport.sendMessage(fcm);
                } else {
                    log.warn("Frame double spending detected");
                }
            }
        } else {
            log.info("sI_{} isn't tracking this frame: Originator: {}, frameId: {}, taskId: {}", transport.getShardIndex(), chain.getOriginatorId(), chain.getFrameId(), taskId );
        }



        if (cntRounds.incrementAndGet() % 100000 == 0)
            log.info("{} training rounds finished...", cntRounds.get());

        // don't forget to remove chain, it'll become a leak otherwise
        chains.remove(chainDesc);
    }


    @Override
    public String targetMessageClass() {
        return CbowRequestMessage.class.getSimpleName();
    }
}
