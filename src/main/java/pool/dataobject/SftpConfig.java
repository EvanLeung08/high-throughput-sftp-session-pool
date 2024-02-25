package pool.dataobject;


import lombok.Data;
import lombok.ToString;

import javax.persistence.*;

@ToString
@Data
@Entity
@Table(name = "SFTP_CONFIG")
public class SftpConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "ID")
    private Long id;
    @Column(name = "HOST")
    private String host;
    @Column(name = "PORT")
    private int port;
    @Column(name = "USERNAME")
    private String username;
    @Column(name = "PASSWORD")
    private String password;
    @Column(name = "MAXSESSIONS")
    private int maxSessions;
    @Column(name = "MAXCHANNELS")
    private int maxChannels;

}