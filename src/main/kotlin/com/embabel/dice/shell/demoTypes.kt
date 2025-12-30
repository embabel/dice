package com.embabel.dice.shell

data class Animal(val name: String, val breed: String)

open class Person(
    val name: String,
    val age: Int,
    val pets: List<Animal> = emptyList(),
    val livesIn: Place? = null,
) {

    override fun toString(): String {
        return "Person(name='$name', age=$age, pets=$pets, livesIn=$livesIn)"
    }
}

class Landlord(
    name: String,
    age: Int,
    pets: List<Animal> = emptyList(),
    val tenants: List<Person> = emptyList(),
    livesIn: Place? = null,
) : Person(name, age, pets, livesIn) {

    override fun toString(): String {
        return "Landlord(name='$name', age=$age, pets=$pets, tenants=$tenants, livesIn=$livesIn)"
    }
}

class Detective(
    name: String,
    age: Int,
    pets: List<Animal> = emptyList(),
    livesIn: Place? = null,
) : Person(name, age, pets, livesIn) {

    override fun toString(): String {
        return "Detective(name='$name', age=$age, pets=$pets, livesIn=$livesIn)"
    }
}

class Criminal(
    name: String,
    age: Int,
    pets: List<Animal> = emptyList(),
    livesIn: Place? = null,
    val crimes: String,
) : Person(name, age, pets, livesIn) {

    override fun toString(): String {
        return "Criminal(name='$name', age=$age, pets=$pets, livesIn=$livesIn, crimes='$crimes')"
    }
}

class Doctor(
    name: String,
    age: Int,
    pets: List<Animal> = emptyList(),
    val specialty: String? = null,
    livesIn: Place? = null,
) : Person(name, age, pets, livesIn) {

    override fun toString(): String {
        return "Doctor(name='$name', age=$age, pets=$pets, specialty=$specialty, livesIn=$livesIn)"
    }
}

class Place(
    val name: String,
) {

    override fun toString(): String {
        return "Place(name='$name')"
    }
}

// Types for Prolog demo with richer relationships
data class Technology(val name: String)

data class Company(val name: String)

/**
 * Person with relationships suitable for Prolog reasoning demos.
 */
data class PrologPerson(
    val name: String,
    val expertIn: List<Technology> = emptyList(),
    val friendOf: List<PrologPerson> = emptyList(),
    val colleagueOf: List<PrologPerson> = emptyList(),
    val reportsTo: PrologPerson? = null,
    val worksAt: Company? = null,
    val livesIn: Place? = null,
)