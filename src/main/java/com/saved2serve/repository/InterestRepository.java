package com.saved2serve.repository;

import com.saved2serve.domain.Interest;

import java.util.List;

public interface InterestRepository {
    void save(Interest interest);

    List<Interest> findByAreaCode(String areaCode);

    boolean existsByEmailHash(String emailHash);
}
