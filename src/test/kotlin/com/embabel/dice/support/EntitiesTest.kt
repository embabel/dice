package com.embabel.dice.support

import com.embabel.dice.text2graph.support.Entities
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test

class EntitiesTest {

    private val jom = jacksonObjectMapper()

    @Test
    fun deserialize() {
        val json = """
            {
              "entities": [
                {
                  "labels": ["Person"],
                  "name": "Rod",
                  "summary": "Rod is a person who has visited many countries, enjoys hobbies such as chess, music, cycling, and hiking, owns a golden retriever named Duke, works as CEO at Embabel, and lives in Annandale with his girlfriend Lynda."
                },
                {
                  "labels": ["Place"],
                  "name": "France",
                  "summary": "France is a country that Rod has visited."
                },
                {
                  "labels": ["Activity"],
                  "name": "chess",
                  "summary": "Chess is one of Rod's hobbies."
                },
               
                {
                  "labels": ["Dog", "Animal"],
                  "name": "Duke",
                  "summary": "Duke is a golden retriever owned by Rod."
                }
              ]
            }
        """.trimIndent()
        val entities = jom.readValue(json, Entities::class.java)
    }

}