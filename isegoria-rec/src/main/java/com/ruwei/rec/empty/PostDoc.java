package com.ruwei.rec.empty;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.Date;
import java.util.List;

@Data
@Document(indexName = "post_index")
public class PostDoc {

    @Id
    private Long id;

    @Field(type = FieldType.Keyword)
    private String postCode;

    @Field(type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Long)
    private Long boardId;

    @Field(type = FieldType.Text, analyzer = "ik_index", searchAnalyzer = "ik_search")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_index", searchAnalyzer = "ik_search")
    private String plainText;

    @Field(type = FieldType.Text, analyzer = "ik_index", searchAnalyzer = "ik_search")
    private List<String> tagNames;

    @Field(type = FieldType.Text, analyzer = "ik_index", searchAnalyzer = "ik_search")
    private String nickname;

    @Field(type = FieldType.Keyword, index = false)
    private String avatar;

    @Field(type = FieldType.Keyword, index = false)
    private String cover;

    @Field(type = FieldType.Integer)
    private Integer type;

    @Field(type = FieldType.Integer)
    private Integer visibility;

    @Field(type = FieldType.Integer)
    private Integer status;

    @Field(type = FieldType.Integer)
    private Integer auditStatus;

    @Field(type = FieldType.Integer)
    private Integer likeCount;

    @Field(type = FieldType.Integer)
    private Integer commentCount;

    @Field(type = FieldType.Integer)
    private Integer collectCount;

    @Field(type = FieldType.Integer)
    private Integer viewCount;

    @Field(type = FieldType.Integer)
    private Integer shareCount;

    @Field(type = FieldType.Double)
    private Double score;

    @Field(type = FieldType.Integer)
    private Integer isTop;

    @Field(type = FieldType.Integer)
    private Integer isEssence;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private Date createdAt;
}