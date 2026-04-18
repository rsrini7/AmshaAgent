package com.arcclaw.core.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import com.arcclaw.persistence.api.SkillDto;
import com.arcclaw.persistence.api.SkillStorePort;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockSkillStorePort implements SkillStorePort {

    private final Map<String, SkillDto> store = new ConcurrentHashMap<>();

    @Override 
    public void save(SkillDto dto) {
        store.put(dto.id(), dto);
    }

    @Override 
    public List<SkillDto> findAll() { 
        return new ArrayList<>(store.values()); 
    }

    @Override 
    public Optional<SkillDto> findByName(String name) { 
        return store.values().stream()
                .filter(s -> name.equals(s.name()))
                .findFirst();
    }

    @Override 
    public boolean deleteById(String id) { 
        return store.remove(id) != null;
    }

    @Override 
    public long deleteAll() { 
        long count = store.size();
        store.clear();
        return count;
    }
}
