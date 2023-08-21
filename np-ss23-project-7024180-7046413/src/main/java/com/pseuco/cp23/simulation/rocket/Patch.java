package com.pseuco.cp23.simulation.rocket;

import com.pseuco.cp23.model.Output;
import com.pseuco.cp23.model.Query;
import com.pseuco.cp23.model.Rectangle;
import com.pseuco.cp23.model.Scenario;
import com.pseuco.cp23.model.Statistics;
import com.pseuco.cp23.model.TraceEntry;
import com.pseuco.cp23.model.XY;
import com.pseuco.cp23.simulation.common.Simulation;
import com.pseuco.cp23.validator.Validator;

import com.pseuco.cp23.simulation.common.Context;
import com.pseuco.cp23.simulation.common.Person;
import com.pseuco.cp23.simulation.common.Person.PersonIDComparator;

import java.util.ArrayList;
import java.util.List;
import java.lang.Thread;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.stream.Collectors;

public class Patch extends Thread implements Simulation, Context {
    private final int id;
    private final int padding;
    private final int syncTicks;

    private final Validator validator;
    private final Scenario scenario;

    private List<Person> population = new ArrayList<>();
    private List<Person> allPopulation = new ArrayList<>();
    private final List<TraceEntry> trace = new ArrayList<TraceEntry>();
    private final Map<String, List<Statistics>> statistics = new HashMap<>();

    private final ArrayList<Channel1Direction> ingoing;
    private final ArrayList<Channel1Direction> outgoing;
    private final Channel1Direction[][] channels;
    private final ArrayList<Integer> whichPatchesToLook; // stores the patch ids of the neighbour patches

    private final Rectangle mainGrid;
    private Rectangle lookingGrid;

    private final ArrayList<Rectangle> obstacles = new ArrayList<Rectangle>();

    public Patch(Scenario scenario, Validator validator, int id, Rectangle grid, int padding,
            Channel1Direction[][] channels, int syncTicks, List<Person> allPopulation) {

        this.mainGrid = grid;        
        this.id = id;
        this.scenario = scenario;
        this.validator = validator;
        this.padding = padding;
        this.channels = channels;
        this.syncTicks = syncTicks;
        this.allPopulation = allPopulation;

        calculateLookingGrid();
        calculateLookingObstacles();
        
        // getting ingoing and outgoing channels
        this.ingoing = new ArrayList<>();
        this.outgoing = new ArrayList<>();
        this.whichPatchesToLook = calculateWhichPatches();
        calculateOutgoingChannels();
        calculateIngoingChannels();

        // initializing and structuring the properties of the object and setting up.
        this.populate();
        this.initializeStatistics();
        this.extendOutput();
    }


    /* 
     * Calculates which patches are our neigbours. Also uses mayPropagateFrom(...) to see if a neighbour is accessible or not.
     * nextGrid and nextId are grid and id of possible neighbours
     */
    private ArrayList<Integer> calculateWhichPatches() {
        ArrayList<Integer> result = new ArrayList<>();
        Iterator<Rectangle> patchIterator = Utils.getPatches(scenario);
        Rectangle nextGrid;
        int nextId = 0;
        while (patchIterator.hasNext()) {
            nextGrid = patchIterator.next();
            if (lookingGrid.overlaps(nextGrid) 
                    && !(nextId == this.id)
                    && com.pseuco.cp23.simulation.common.Utils.mayPropagateFrom(scenario, mainGrid, nextGrid)) {
                result.add(nextId);
            }
            nextId++;
        }
        return result;
    }

    /* 
     * These two functions are to get the channels for ingoing and outgoing channels, list[a][b] is representing an outgoing channel from a to b
     */
    private void calculateOutgoingChannels() {
        for (int i : whichPatchesToLook) {
            this.outgoing.add(this.channels[this.id][i]);
        }
    }
    private void calculateIngoingChannels() {
        for (int i : whichPatchesToLook) {
            this.ingoing.add(this.channels[i][this.id]);
        }
    }

    /* 
     * This method returns the obstacles in the area we are simulating.
     */
    private void calculateLookingObstacles() {
        for (Rectangle obstacle : this.scenario.getObstacles()) {
            if (this.lookingGrid.overlaps(obstacle)) {
                this.obstacles.add(obstacle);
            }
        }
    }

    /* 
     * This method calculates and returns the grid with the padding added.
     */
    private void calculateLookingGrid() {
        XY topLeft = this.mainGrid.getTopLeft();
        XY bottomRight = this.mainGrid.getBottomRight();

        int beginTopLeftX = Math.max(topLeft.getX() - this.padding, 0);
        int beginTopLeftY = Math.max(topLeft.getY() - this.padding, 0);
        XY newTopLeft = new XY(beginTopLeftX, beginTopLeftY);

        int lastBottomRightX = Math
                .min(bottomRight.getX() + this.padding, this.scenario.getGrid().getBottomRight().getX());
        int lastBottomRightY = Math
                .min(bottomRight.getY() + this.padding, this.scenario.getGrid().getBottomRight().getY());
        XY newBottomRight = new XY(lastBottomRightX, lastBottomRightY);

        this.lookingGrid = new Rectangle(newTopLeft, newBottomRight.sub(newTopLeft));
    }

    /* 
     * cloning people to inner population according to papulation and taking simulation area to account
     */
    private void populate() {
        for (Person popPerson : allPopulation) {
            if (lookingGrid.contains(popPerson.getPosition())) {
                population.add(popPerson.clone(this));
            }
        }

    }

    /* 
     * Same as slug class' initializeStatistics() function
     */
    private void initializeStatistics() {
        for (String queryKey : this.scenario.getQueries().keySet()) {
            this.statistics.put(queryKey, new ArrayList<>());
        }
    }

    /* 
     * Collecting statistics based on the current SI²R values
     * Also checks if mainGrid contains the person since we only want a patch's mainGrid to be included in statistics
     */
    private void extendStatistics() {
        for (Map.Entry<String, Query> entry : this.scenario.getQueries().entrySet()) {
            final Query query = entry.getValue();
            this.statistics.get(entry.getKey()).add(new Statistics(
                    this.population.stream().filter(
                        (Person person) -> person.isSusceptible()
                                && query.getArea().contains(person.getPosition())
                                && mainGrid.contains(person.getPosition())
                    ).count(),
                    this.population.stream().filter(
                        (Person person) -> person.isInfected()
                                && query.getArea().contains(person.getPosition())
                                && mainGrid.contains(person.getPosition())
                    ).count(),
                    this.population.stream().filter(
                        (Person person) -> person.isInfectious()
                                && query.getArea().contains(person.getPosition())
                                && mainGrid.contains(person.getPosition())
                    ).count(),
                    this.population.stream().filter(
                        (Person person) -> person.isRecovered()
                                && query.getArea().contains(person.getPosition())
                                && mainGrid.contains(person.getPosition())
                    ).count()
            ));
        }
    }

    /* 
     * Extends the statists and the trace for the current tick
     * Checks if we are collecting traces
     */
    private void extendOutput() {
        if (this.scenario.getTrace()) {
            this.population.sort(new PersonIDComparator());
            this.trace.add(
                new TraceEntry(
                        this.population.stream()
                                .filter((Person person) -> mainGrid.contains(person.getPosition()))
                                .map(Person::getInfo)
                                .collect(Collectors.toList())
                )
            );
        }
        this.extendStatistics();
    }

    /* 
     * Run function of our patch.
     * If the time has come to sync, we first send and then get information from others.
     * Then we write the output.
     */
    public void run() {
        int step = 0; // all ticks
        while (step < scenario.getTicks()) {
            validator.onPatchTick(step, this.id);
            this.tick(step);
            step++;
            if (step % syncTicks == 0) {
                sendInformationToOthers();
                getInformationFromOthers();
            }
            this.extendOutput();
        }
    }

    /* 
     * Same tick() function as slug
     */
    private void tick(int step) {
        for (Person person : this.population) {
            validator.onPersonTick(step, this.id, person.getId());
            person.tick();
        }

        // bust the ghosts of all persons
        this.population.stream().forEach(Person::bustGhost);

        // now compute how the infection spreads between the population
        for (int i = 0; i < this.population.size(); i++) {
            for (int j = i + 1; j < this.population.size(); j++) {
                final Person iPerson = this.population.get(i);
                final Person jPerson = this.population.get(j);
                final XY iPosition = iPerson.getPosition();
                final XY jPosition = jPerson.getPosition();
                final int deltaX = Math.abs(iPosition.getX() - jPosition.getX());
                final int deltaY = Math.abs(iPosition.getY() - jPosition.getY());
                final int distance = deltaX + deltaY;
                if (distance <= this.scenario.getParameters().getInfectionRadius()) {
                    if (iPerson.isInfectious() && iPerson.isCoughing() && jPerson.isBreathing()) {
                        jPerson.infect();
                    }
                    if (jPerson.isInfectious() && jPerson.isCoughing() && iPerson.isBreathing()) {
                        iPerson.infect();
                    }
                }
            }
        }
    }

    /* 
     * First takes the people in its field then adds people from other channels
     * Sorts people before setting them so that statistics are correct
     */
    private void getInformationFromOthers() {
        ArrayList<Person> newPeople = getPeopleFromYourField();
        for (Channel1Direction ingoingChannel : ingoing) {
            try {
                ArrayList<Person> temp = ingoingChannel.get();
                for (Person person : temp) {
                    if (this.lookingGrid.contains(person.getPosition()) && !this.mainGrid.contains(person.getPosition()))
                        newPeople.add(person.clone(this));
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        newPeople.sort(new PersonIDComparator());
        this.population = newPeople;
    }


    /* 
     * Sending our grid's information to others, no sorting is needed here as we do it in getInformationFromOthers() function
     */
    private void sendInformationToOthers() {
        ArrayList<Person> myPeople = getPeopleFromYourField();

        for (Channel1Direction outGoing : outgoing) {
            try {
                outGoing.set(myPeople);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /* 
     * Simple method to get the people from our field while cloning them so that no two patches access the same person object
     */
    private ArrayList<Person> getPeopleFromYourField() {
        ArrayList<Person> result = new ArrayList<>();

        for (Person person : this.population) {
            if (this.mainGrid.contains(person.getPosition())) {
                result.add(person.clone(this));
            }
        }
        return result;
    }

    @Override
    public Rectangle getGrid() {
        return this.lookingGrid;
    }

    @Override
    public List<Rectangle> getObstacles() {
        return this.obstacles;
    }

    @Override
    public List<Person> getPopulation() {
        return this.population;
    }

    @Override
    public Output getOutput() {
        return new Output(scenario, trace, statistics);
    }

}
