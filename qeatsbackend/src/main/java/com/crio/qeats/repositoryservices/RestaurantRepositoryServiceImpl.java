/*
 *
 *  * Copyright (c) Crio.Do 2019. All rights reserved
 *
 */

package com.crio.qeats.repositoryservices;

import ch.hsr.geohash.GeoHash;
import com.crio.qeats.configs.RedisConfiguration;
import com.crio.qeats.dto.Restaurant;
import com.crio.qeats.globals.GlobalConstants;
import com.crio.qeats.models.RestaurantEntity;
import com.crio.qeats.repositories.RestaurantRepository;
import com.crio.qeats.utils.GeoLocation;
import com.crio.qeats.utils.GeoUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Provider;

import org.apache.tomcat.util.codec.binary.StringUtils;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;


@Service
public class RestaurantRepositoryServiceImpl implements RestaurantRepositoryService {

  @Autowired
  private RestaurantRepository restaurantRepository;

  @Autowired
  private RedisConfiguration redisConfiguration;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private Provider<ModelMapper> modelMapperProvider;

  private boolean isOpenNow(LocalTime time, RestaurantEntity res) {
    LocalTime openingTime = LocalTime.parse(res.getOpensAt());
    LocalTime closingTime = LocalTime.parse(res.getClosesAt());

    return time.isAfter(openingTime) && time.isBefore(closingTime);
  }



  private List<Restaurant> findAllRestaurantsCloseFromDb(Double latitude, Double longitude, 
      LocalTime currentTime, Double servingRadiusInKms) {
    
    List<Restaurant> restaurantList = new ArrayList<>();
    ModelMapper modelMapper = modelMapperProvider.get();
    //List<RestaurantEntity> restaurantEntities = mongoTemplate.findAll(RestaurantEntity.class);
    for (RestaurantEntity restaurantEntity : restaurantRepository.findAll()) {
      if (isRestaurantCloseByAndOpen(restaurantEntity, currentTime, latitude,
          longitude, servingRadiusInKms)) {
        restaurantList.add(modelMapper.map(restaurantEntity, Restaurant.class));
      }
    }

    
    String createdJsonString = "";

    redisConfiguration.initCache();

    try {
      createdJsonString = new ObjectMapper().writeValueAsString(restaurantList);
      System.out.println("Helllo");
    } catch (IOException e) {
      e.printStackTrace();
    }

    JedisPool jedispool = redisConfiguration.getJedisPool();
    Jedis jedis = null;
    try {
      jedis = jedispool.getResource();
    } catch (Exception e) {
      e.printStackTrace();
    }

    GeoLocation geoLocation = new GeoLocation(latitude, longitude);
    GeoHash geoHash = GeoHash.withCharacterPrecision(geoLocation.getLatitude(),
        geoLocation.getLongitude(), 7);

    jedis.set(geoHash.toBase32(), createdJsonString);
    return restaurantList;
  }

  private List<Restaurant> findAllRestaurantsCloseByFromCache(
      Double latitude, Double longitude, LocalTime currentTime, Double servingRadiusInKms) {

    List<Restaurant> restaurantList = new ArrayList<>();


    JedisPool jedispool = redisConfiguration.getJedisPool();
    Jedis jedis = null;
    try {
      jedis = jedispool.getResource();
    } catch (Exception e) {
      e.printStackTrace();
    }
    
    GeoLocation geoLocation = new GeoLocation(latitude, longitude);
    GeoHash geoHash = GeoHash.withCharacterPrecision(geoLocation.getLatitude(),
        geoLocation.getLongitude(), 7);

    if (!(jedis.exists(geoHash.toBase32()))) {
      return findAllRestaurantsCloseFromDb(latitude, longitude, currentTime, servingRadiusInKms);
    }

    String jsonStringFromCache = "";

    try {
      jsonStringFromCache = jedis.get(geoHash.toBase32());
      restaurantList = new ObjectMapper().readValue(jsonStringFromCache, 
          new TypeReference<List<Restaurant>>() {
            });
    } catch (IOException e) {
      e.printStackTrace();
    }

    return restaurantList;
  }


  public List<Restaurant> findAllRestaurantsCloseBy(Double latitude,
      Double longitude, LocalTime currentTime, Double servingRadiusInKms) {

    if (redisConfiguration.isCacheAvailable()) {
      return findAllRestaurantsCloseByFromCache(latitude, longitude, currentTime,
          servingRadiusInKms);
    } else {
      return findAllRestaurantsCloseFromDb(latitude, longitude, currentTime, servingRadiusInKms);
    }
  }








  // TODO: CRIO_TASK_MODULE_NOSQL
  // Objective:
  // 1. Check if a restaurant is nearby and open. If so, it is a candidate to be returned.
  // NOTE: How far exactly is "nearby"?

  /**
   * Utility method to check if a restaurant is within the serving radius at a given time.
   * @return boolean True if restaurant falls within serving radius and is open, false otherwise
   */
  private boolean isRestaurantCloseByAndOpen(RestaurantEntity restaurantEntity,
      LocalTime currentTime, Double latitude, Double longitude, Double servingRadiusInKms) {
    if (isOpenNow(currentTime, restaurantEntity)) {
      return GeoUtils.findDistanceInKm(latitude, longitude,
          restaurantEntity.getLatitude(), restaurantEntity.getLongitude())
          < servingRadiusInKms;
    }

    return false;
  }



}

