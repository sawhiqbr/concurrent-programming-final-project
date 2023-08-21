package com.pseuco.cp23.simulation.rocket;

import com.pseuco.cp23.model.Output;
import com.pseuco.cp23.model.Scenario;
import com.pseuco.cp23.model.Rectangle;
import com.pseuco.cp23.model.PersonInfo;
import com.pseuco.cp23.model.Statistics;
import com.pseuco.cp23.model.TraceEntry;
import com.pseuco.cp23.simulation.common.Context;
import com.pseuco.cp23.simulation.common.Person;
import com.pseuco.cp23.simulation.common.Simulation;
import com.pseuco.cp23.validator.InsufficientPaddingException;
import com.pseuco.cp23.validator.Validator;

import java.lang.Math;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;

/**
 * Your implementation shall go into this class.
 *
 * <p>
 * This class has to implement the <em>Simulation</em> interface.
 * </p>
 */
public class Rocket implements Simulation, Context {

    private int padding;
    private Scenario scenario;
    private Validator validator;
    private int syncTicks;

    private final List<Person> population = new ArrayList<>();
    private final List<TraceEntry> trace = new ArrayList<>();
    private final Map<String, List<Statistics>> statistics = new HashMap<>();

    /**
     * Constructs a rocket with the given parameters.
     *
     * <p>
     * You must not change the signature of this constructor.
     * </p>
     *
     * <p>
     * Throw an insufficient padding exception if and only if the padding is insufficient.
     * Hint: Depending on the parameters, some amount of padding is required even if one
     * only computes one tick concurrently. The padding is insufficient if the provided
     * padding is below this minimal required padding.
     * </p>
     *
     * @param scenario  The scenario to simulate.
     * @param padding   The padding to be used.
     * @param validator The validator to be called.
     */
    public Rocket(Scenario scenario, int padding, Validator validator) throws InsufficientPaddingException {
        this.scenario = scenario;
        this.padding = padding;
        this.validator = validator;
        this.syncTicks = calcSyncTicks();
        this.populate();
    }

    /*
     * Calculates number of ticks to simulate before synchronization taking several things into account.
     */
    private int calcSyncTicks() throws InsufficientPaddingException {
        int syncTicks = 0;
        double uncertainity = 0;

        while (uncertainity <= padding) {
            syncTicks++;
            uncertainity = 2 * syncTicks + scenario.getParameters().getInfectionRadius()
                    * Math.ceil( (double) syncTicks / scenario.getParameters().getIncubationTime());
        }

        if (uncertainity > padding){
            syncTicks--;
        }

        if (syncTicks == 0) {
            throw new InsufficientPaddingException(padding);
        }

        return syncTicks;
    }

    /* 
     * We populate the context with persons based on the respective info objects
     */
    private void populate() {
        int id = 0;
        for (PersonInfo personInfo : this.scenario.getPopulation()) {
            this.population.add(
                new Person(id, this, this.scenario.getParameters(), personInfo)
            );
            id++;
        }
    }

    @Override
    public Output getOutput() {
        return new Output(this.scenario, this.trace, this.statistics);
    }

    /* 
     * Here we initialize and run patches,
     * giving all of them our channel array,
     * then we start and wait for them to finish their job,
     * after all are joined, we run our output creator
     */
    @Override
    public void run() {
        int numOfPartitions = this.scenario.getNumberOfPatches();
        Channel1Direction channels[][] = createChannels(numOfPartitions);
        ArrayList<Patch> patches = createPatches(numOfPartitions, channels);

        for (Patch patch : patches) {
            patch.start();
        }

        for (Patch patch : patches) {
            try {
                patch.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        writeOutputs(patches);
    }

    /* 
     * This function creates patches according to their neighbours, neighbour calculation happens inside 
     * Patch(...) creator function
     */
    private ArrayList<Patch> createPatches(int numOfPartitions, Channel1Direction[][] channels) {
        Iterator<Rectangle> patchIterator = Utils.getPatches(this.scenario);

        ArrayList<Patch> patches = new ArrayList<Patch>();

        for (int id = 0; id < numOfPartitions; id++) {
            patches.add(new Patch(scenario, validator, id, patchIterator.next(), padding, channels, syncTicks,
                    population));
        }
        return patches;
    }

    /* 
     * Simple function to fill in the channels array we have created with channel objects
     */
    private Channel1Direction[][] createChannels(int numOfPartitions) {
        Channel1Direction channels[][] = new Channel1Direction[numOfPartitions][numOfPartitions];
        for (int i = 0; i < numOfPartitions; i++) {
            for (int k = 0; k < numOfPartitions; k++) {
                channels[i][k] = new Channel1Direction();
            }
        }
        return channels;
    }

    /* 
     * This function is to get the outputs from the patches and write the results in the rocket object's output properly
     * allPersonInfo: holds (will hold) person info for all of the patches
     * tempOutput: output of the patch we are currently analizing (tempStatistics and tempTraceList are self explanotory)
     * we are extending the traces and statistics from the information from each patch
     */
    private void writeOutputs(ArrayList<Patch> patches) {
        ArrayList<ArrayList<PersonInfo>> allPersonInfo = new ArrayList<ArrayList<PersonInfo>>();

        for (int i = 0; i < this.scenario.getTicks()+1; i++) {
            allPersonInfo.add(new ArrayList<PersonInfo>());
        }

        for (Patch patch : patches) {
            Output tempOutput = patch.getOutput();
            Map<String, List<Statistics>> tempStatistics = tempOutput.getStatistics();
            List<TraceEntry> tempTraceList = tempOutput.getTrace();
            
            // merging statistics
            for (Map.Entry<String, List<Statistics>> entry : tempStatistics.entrySet()) {
                String key = entry.getKey();
                List<Statistics> patchStats = entry.getValue();
            
                if (statistics.containsKey(key)) {
                    int i = 0;
                    for (Statistics existingStat : statistics.get(key)) {
                        existingStat = new Statistics(existingStat.getSusceptible() + patchStats.get(i).getSusceptible(),
                                              existingStat.getInfected() + patchStats.get(i).getInfected(),
                                              existingStat.getInfectious() + patchStats.get(i).getInfectious(),
                                              existingStat.getRecovered() + patchStats.get(i).getRecovered());
                        statistics.get(key).set(i, existingStat);
                        i++;
                    }
                } else {
                    statistics.put(key, patchStats);
                }
            }
           
            // merging traces
            int index = 0;
            for (TraceEntry traceEntry : tempTraceList) {
                allPersonInfo.get(index).addAll(traceEntry.getPopulation());
                index++;
            }
        }

        // writing info to the trace of the class.
        if (this.scenario.getTrace()) {
            for (ArrayList<PersonInfo> personInfos : allPersonInfo) {
                List<PersonInfo> emptyInfo = new ArrayList<>();
                TraceEntry tempTraceEntry = new TraceEntry(emptyInfo);

                // for each trace entry, get the population names in order
                for (int i = 0; i < this.population.size(); i++) { 
                    String orderedName = this.population.get(i).getName();
                    for (int k = 0; k < this.population.size(); k++) {
                        if (orderedName.equals(personInfos.get(k).getName())) {
                            tempTraceEntry.getPopulation().add(personInfos.get(k));
                        }
                    } 
                }
                this.trace.add(tempTraceEntry);
            }
        }
    }

    @Override
    public Rectangle getGrid() {
        return this.scenario.getGrid();
    }

    @Override
    public List<Rectangle> getObstacles() {
        return this.scenario.getObstacles();
    }

    @Override
    public List<Person> getPopulation() {
        return this.population;
    }
}
