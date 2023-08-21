package com.pseuco.cp23.simulation.rocket;

import com.pseuco.cp23.simulation.common.Person;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class Channel1Direction {
    private ArrayList<Person> persons;
    private final AtomicBoolean flag;
    
    /*
     * This class is for communcation between patches. See wiki for more info.
     */
    public Channel1Direction() { 
        this.persons = new ArrayList<>();
        this.flag = new AtomicBoolean(false); // true if filled
    }

    public synchronized ArrayList<Person> get() throws InterruptedException {
        while (!flag.get()) { 
            wait();
        }
        flag.set(false);
        notify();
        return persons;
    }

    public synchronized void set(ArrayList<Person> newPersons) throws InterruptedException {
        while (flag.get()) {
            wait();
        }
        this.persons = newPersons;
        flag.set(true);
        notify();
    }
}