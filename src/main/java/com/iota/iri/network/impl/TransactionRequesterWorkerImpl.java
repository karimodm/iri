package com.iota.iri.network.impl;

import com.google.common.annotations.VisibleForTesting;
import com.iota.iri.controllers.TipsViewModel;
import com.iota.iri.controllers.TransactionViewModel;
import com.iota.iri.model.Hash;
import com.iota.iri.network.Neighbor;
import com.iota.iri.network.Node;
import com.iota.iri.network.TransactionRequester;
import com.iota.iri.network.TransactionRequesterWorker;
import com.iota.iri.storage.Tangle;
import com.iota.iri.utils.thread.DedicatedScheduledExecutorService;
import com.iota.iri.utils.thread.SilentScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 * Creates a background worker that tries to work through the request queue by sending random tips along the requested
 * transactions.
 * </p>
 * <p>
 * This massively increases the sync speed of new nodes that would otherwise be limited to requesting in the same rate
 * as new transactions are received.
 * </p>
 * Note: To reduce the overhead for the node we only trigger this worker if the request queue gets bigger than the
 *       {@link #REQUESTER_THREAD_ACTIVATION_THRESHOLD}. Otherwise we rely on the processing of the queue due to normal
 *       outgoing traffic like transactions that get relayed by our node.
 */
public class TransactionRequesterWorkerImpl implements TransactionRequesterWorker {
    /**
     * The minimum amount of transactions in the request queue that are required for the worker to trigger.
     */
    public static final int REQUESTER_THREAD_ACTIVATION_THRESHOLD = 50;

    /**
     * The time (in milliseconds) that the worker waits between its iterations.
     */
    private static final int REQUESTER_THREAD_INTERVAL = 100;

    /**
     * The logger of this class (a rate limited logger than doesn't spam the CLI output).
     */
    private static final Logger log = LoggerFactory.getLogger(TransactionRequesterWorkerImpl.class);

    /**
     * The Tangle object which acts as a database interface.
     */
    private final Tangle tangle;

    /**
     * The manager for the requested transactions that allows us to access the request queue.
     */
    private final TransactionRequester transactionRequester;

    /**
     * Manager for the tips (required for selecting the random tips).
     */
    private final TipsViewModel tipsViewModel;

    /**
     * The network manager of the node.
     */
    private final Node node;

    /**
     * The manager of the background task.
     */
    private final SilentScheduledExecutorService executorService = new DedicatedScheduledExecutorService(
            "Transaction Requester", log);

    /**
     * @param tangle Tangle object which acts as a database interface
     * @param transactionRequester manager for the requested transactions
     * @param tipsViewModel the manager for the tips
     * @param node the network manager of the node
     */
    public TransactionRequesterWorkerImpl(Tangle tangle, TransactionRequester transactionRequester,
            TipsViewModel tipsViewModel, Node node) {
        this.tangle = tangle;
        this.transactionRequester = transactionRequester;
        this.tipsViewModel = tipsViewModel;
        this.node = node;
    }

    /**
     * {@inheritDoc}
     * <p>
     * To reduce the overhead for the node we only trigger this worker if the request queue gets bigger than the {@link
     * #REQUESTER_THREAD_ACTIVATION_THRESHOLD}. Otherwise we rely on the processing of the queue due to normal outgoing
     * traffic like transactions that get relayed by our node.
     * </p>
     */
    @Override
    public boolean processRequestQueue() {
        try {
            if (isActive()) {
                TransactionViewModel transaction = getTransactionToSendWithRequest();
                if (isValidTransaction(transaction)) {
                    sendToNodes(transaction);
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("unexpected error while processing the request queue", e);
        }
        return false;
    }

    private void sendToNodes(TransactionViewModel transaction) {
        for (Neighbor neighbor : node.getNeighbors()) {
            try {
                // automatically adds the hash of a requested transaction when sending a packet
                node.sendPacket(transaction, neighbor);
            } catch (Exception e) {
                log.error("unexpected error while sending request to neighbour", e);
            }
        }
    }

    @VisibleForTesting
    boolean isActive() {
        return transactionRequester.numberOfTransactionsToRequest() >= REQUESTER_THREAD_ACTIVATION_THRESHOLD;
    }

    @VisibleForTesting
    boolean isValidTransaction(TransactionViewModel transaction) {
        return transaction != null && (
                transaction.getType() != TransactionViewModel.PREFILLED_SLOT
             || transaction.getHash().equals(Hash.NULL_HASH));
    }                                     

    @Override
    public void start() {
        executorService.silentScheduleWithFixedDelay(this::processRequestQueue, 0, REQUESTER_THREAD_INTERVAL,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void shutdown() {
        executorService.shutdownNow();
    }

    /**
     * <p>
     * Retrieves a random solid tip that can be sent together with our request.
     * </p>
     * </p>
     * It retrieves the hash of the tip from the {@link #tipsViewModel} and tries to load it from the
     * database.
     * </p>
     *
     * @return a random tip
     * @throws Exception if anything unexpected happens while trying to retrieve the random tip.
     */
    @VisibleForTesting
    TransactionViewModel getTransactionToSendWithRequest() throws Exception {
        Hash tip = tipsViewModel.getRandomSolidTipHash();
        if (tip == null) {
            tip = tipsViewModel.getRandomNonSolidTipHash();
        }

        return TransactionViewModel.fromHash(tangle, tip == null ? Hash.NULL_HASH : tip);
    }
}
